package com.reucon.openfire.plugin.archive.xep0313;

import com.reucon.openfire.plugin.archive.ArchiveProperties;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import com.reucon.openfire.plugin.archive.xep.AbstractIQHandler;
import com.reucon.openfire.plugin.archive.xep0059.XmppResultSet;
import org.dom4j.*;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.PacketRouter;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.disco.ServerFeaturesProvider;
import org.jivesoftware.openfire.forward.Forwarded;
import org.jivesoftware.openfire.muc.MUCRole;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.PacketError;

import java.text.ParseException;
import java.time.*;
import java.time.Instant;
import java.util.*;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * XEP-0313 IQ Query Handler
 */
abstract class IQQueryHandler extends AbstractIQHandler implements
        ServerFeaturesProvider {

    private static final Logger Log = LoggerFactory.getLogger(IQQueryHandler.class);

    // TODO replace this when SystemProperty instances work nice together with Plugins (probably in Openfire 4.5.1).
//    public static final SystemProperty<Boolean> PROP_ALLOW_UNRECOGNIZED_SEARCH_FIELDS = SystemProperty.Builder.ofType( Boolean.class )
//        .setKey( "monitoring.search.allow-unrecognized-fields" )
//        .setDynamic(true)
//        .setDefaultValue(false)
//        .setPlugin("monitoring")
//        .build();
    public static final String PROP_ALLOW_UNRECOGNIZED_SEARCH_FIELDS = "monitoring.search.allow-unrecognized-fields";

    protected final String NAMESPACE;
    protected ExecutorService executorService;
    protected PacketRouter router;

    private final XMPPDateTimeFormat xmppDateTimeFormat = new XMPPDateTimeFormat();

    IQQueryHandler(final String moduleName, final String namespace) {
        super(moduleName, "query", namespace);
        NAMESPACE = namespace;
    }

    @Override
    public void initialize( XMPPServer server )
    {
        super.initialize( server );
        executorService = Executors.newCachedThreadPool( new NamedThreadFactory( "message-archive-handler-", null, null, null ) );
        router = server.getPacketRouter();
    }

    @Override
    public void stop()
    {
        executorService.shutdown();
        super.stop();
    }

    @Override
    public void destroy()
    {
        // Give the executor some time to finish processing jobs.
        final long end = System.currentTimeMillis() + 4000;
        while ( !executorService.isTerminated() && System.currentTimeMillis() < end )
        {
            try
            {
                Thread.sleep( 100 );
            }
            catch ( InterruptedException e )
            {
                break;
            }
        }
        executorService.shutdownNow();
        super.destroy();
    }

    public IQ handleIQ( final IQ packet ) throws UnauthorizedException {

        if(packet.getType().equals(IQ.Type.get)) {
            return buildSupportedFieldsResult(packet);
        }

        // Default to user's own archive
        JID archiveJid = packet.getTo();
        if (archiveJid == null) {
            archiveJid = packet.getFrom().asBareJID();
        }
        Log.debug("Archive requested is: {}", archiveJid);

        // Parse the request.
        QueryRequest queryRequest = new QueryRequest(packet.getChildElement(), archiveJid);

        if ( queryRequest.getDataForm() != null ) {
            final List<String> supportedFieldNames = getSupportedFieldVariables();
            final Set<String> unsupported = queryRequest.getDataForm().getFields().stream()
                .filter( f -> f.getFirstValue() != null && !f.getFirstValue().isEmpty() ) // Allow unsupported, but empty fields.
                .map(FormField::getVariable)
                .filter(v -> !supportedFieldNames.contains(v))
                .collect(Collectors.toSet());

            Log.debug( "Found {} unsupported field names{}", unsupported.size(), unsupported.isEmpty() ? "." : ": " + String.join(", ", unsupported));

            if ( !JiveGlobals.getBooleanProperty(PROP_ALLOW_UNRECOGNIZED_SEARCH_FIELDS, false) && !unsupported.isEmpty() ) {
                return buildErrorResponse(packet, PacketError.Condition.bad_request, "Unsupported field(s): " + String.join(", ", unsupported));
            }
        }

        // Now decide the type.
        MultiUserChatService service = null;
        MUCRoom room = null;
        if (!XMPPServer.getInstance().isLocal(archiveJid)) {
            Log.debug("Archive '{}' does not relate to a local user.", archiveJid);
            service = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(archiveJid);
            if ( service != null ) {
                room = service.getChatRoom(archiveJid.getNode());
            }

            if (room == null) {
                Log.debug("Archive '{}' does not relate to a recognized MUC service on this domain.", archiveJid);
                return buildErrorResponse(packet, PacketError.Condition.item_not_found, "The archive '" + archiveJid + "' cannot be found or is not accessible.");
            } else {
                Log.debug("Archive '{}' relates to a recognized MUC room on this domain.", archiveJid);
            }
        } else {
            Log.debug("Archive '{}' relates to a user account on this domain.", archiveJid);
        }

        JID requestor = packet.getFrom().asBareJID();

        // Auth checking.
        if(room != null) {
            boolean pass = false;
            if (service.isSysadmin(requestor)) {
                pass = true;
            }
            MUCRole.Affiliation aff = room.getAffiliation(requestor);
            if (aff != MUCRole.Affiliation.outcast) {
                if (aff == MUCRole.Affiliation.owner || aff == MUCRole.Affiliation.admin) {
                    pass = true;
                } else if (room.isMembersOnly()) {
                    if (aff == MUCRole.Affiliation.member) {
                        pass = true;
                    }
                } else {
                    pass = true;
                }
            }
            if (!pass) {
                Log.debug("Unable to process query as requestor '{}' is forbidden to retrieve archive for room '{}'.", requestor, archiveJid);
                return buildErrorResponse(packet, PacketError.Condition.forbidden, "You are currently not allowed to access the archive of room '" + room.getJID() + "'.");
            }

            // Filtering by JID should only be available to entities that would already have been allowed to know the publisher
            // of the events (e.g. this could not be used by a visitor to a semi-anonymous MUC).
            final MUCRole occupant = room.getOccupantByFullJID(packet.getFrom());
            if ( !room.canAnyoneDiscoverJID() ) {
                final FormField withValue = queryRequest.getDataForm().getField("with");
                if ( withValue != null && withValue.getFirstValue() != null && !withValue.getFirstValue().isEmpty() ) {
                    final JID with;
                    try {
                        with = new JID(withValue.getFirstValue());
                    } catch ( IllegalArgumentException ex ) {
                        return buildErrorResponse(packet, PacketError.Condition.bad_request, "The value of the 'with' field must be a valid JID (but is not).");
                    }

                    // Unless the requestor is a moderator, or is filtering by it's own JID, disallow the request.
                    final boolean isModerator = occupant != null && occupant.getRole() == MUCRole.Role.moderator;
                    final boolean isFilteringByOwnJid = with.asBareJID().equals( packet.getFrom().asBareJID() );
                    if ( !isModerator && !isFilteringByOwnJid ) {
                        Log.debug("Unable to process query as requestor '{}' is not a moderator of the MUC room '{}', and is filtering by JID '{}' which is not its own.", requestor, archiveJid, with);
                        return buildErrorResponse(packet, PacketError.Condition.forbidden, "You are currently not allowed to filter the the archive of room '" + room.getJID() + "' by JID.");
                    }
                }
            }

            // Password protected room
            if (room.isPasswordProtected())  {
                // check whether requestor is occupant in the room
                if (occupant == null) {
                    // no occupant so currently not authenticated to query archive
                    Log.debug("Unable to process query as requestor '{}' is currently not authenticated for this password protected room '{}'.", requestor, archiveJid);
                    return buildErrorResponse(packet, PacketError.Condition.forbidden, "You are currently not allowed to access the archive of room '" + room.getJID() + "'.");
                }
            }
        } else if(!archiveJid.equals(requestor)) { // Not user's own
            // ... disallow unless admin.
            if (!XMPPServer.getInstance().getAdmins().contains(requestor)) {
                Log.debug("Unable to process query as requestor '{}' is forbidden to retrieve personal archives other than his own. Unable to access archives of '{}'.", requestor, archiveJid);
                return buildErrorResponse(packet, PacketError.Condition.forbidden, "You are not allowed to access the archive of '" + archiveJid + "'.");
            }
        }

        if (queryRequest.getResultSet() != null && queryRequest.getResultSet().getIndex() != null) {
            Log.debug("Unable to process query for a result page that is being retrieved 'out of order'. This feature is not supported.");
            return buildErrorResponse(packet, PacketError.Condition.feature_not_implemented, "Retrieving pages 'out of order' is not supported.");
        }

        sendMidQuery(packet);

        // Modify original request to force result set management to be applied.
        if ( JiveGlobals.getBooleanProperty( ArchiveProperties.FORCE_RSM, true ) ) {
            final QName seQName = QName.get("set", XmppResultSet.NAMESPACE);
            if ( packet.getChildElement().element(seQName ) == null ) {
                packet.getChildElement().addElement( seQName );
            }
        }
        queryRequest = new QueryRequest(packet.getChildElement(), archiveJid);

        // OF-1200: make sure that data is flushed to the database before retrieving it.
        final MonitoringPlugin plugin = (MonitoringPlugin) XMPPServer.getInstance().getPluginManager().getPlugin(MonitoringConstants.NAME);
        final ConversationManager conversationManager = (ConversationManager)plugin.getModule( ConversationManager.class);
        final Instant targetEndDate = Instant.now(); // TODO or, the timestamp of the element referenced by 'before' from RSM, if that's set.

        final QueryRequest finalQueryRequest = queryRequest;
        executorService.submit(() -> {
            try
            {
                Log.debug("Retrieving messages from archive...");
                Duration eta;
                Duration totalPause = Duration.ZERO;
                Instant start = Instant.now();
                while ( !(eta = conversationManager.availabilityETA( targetEndDate )).isZero() )
                {
                    try
                    {
                        Log.trace( "Not all data that is being requested has been written to the database yet. Delaying request processing for {}", eta );
                        Thread.sleep( eta.toMillis() );
                        totalPause = totalPause.plus( eta );
                    }
                    catch ( InterruptedException e )
                    {
                        Log.warn( "Interrupted wait for data availability. Data might be incomplete!", e );
                        break;
                    }
                }
                Log.debug( "All data that has been requested has been written to the database. Proceed to process request." );

                Collection<ArchivedMessage> archivedMessages = retrieveMessages(finalQueryRequest);
                Log.debug("Retrieved {} messages from archive.", archivedMessages.size());

                for(ArchivedMessage archivedMessage : archivedMessages) {
                    sendMessageResult(packet.getFrom(), finalQueryRequest, archivedMessage);
                }

                sendEndQuery(packet, packet.getFrom(), finalQueryRequest);
                Log.debug("Done with request. The request took {} to complete, of which {} was spend waiting on data to be written to the database.", Duration.between( start, Instant.now()), totalPause );
            }
            catch ( NotFoundException e ) {
                Log.debug( "Request resulted in a item-not-found condition.", e );
                try {
                    router.route( buildErrorResponse(packet, PacketError.Condition.item_not_found, e.getMessage() ) );
                } catch ( Exception ex ) {
                    Log.error( "An unexpected exception occurred while returning an error stanza to the originator of: {}", packet, ex );
                }
            }
            catch ( Exception e ) {
                Log.error( "An unexpected exception occurred while processing: {}", packet, e );
                if (packet.isRequest()) {
                    try {
                        router.route( buildErrorResponse(packet, PacketError.Condition.internal_server_error, "An unexpected exception occurred while processing a request to retrieve archived messages." ) );
                    } catch ( Exception ex ) {
                        Log.error( "An unexpected exception occurred while returning an error stanza to the originator of: {}", packet, ex );
                    }
                }
            }
        } );

        return null;
    }

    protected void sendMidQuery(IQ packet) {
        // Default: Do nothing.
    }

    protected abstract void sendEndQuery(IQ packet, JID from, QueryRequest queryRequest);

    /**
     * Create error response due to forbidden request.
     *
     * @param packet Received request (cannot be null).
     * @param condition the condition (which implies the type) of the error (cannot be null).
     * @param message A human-readable text describing the error (can be null).
     * @return The error response (never null).
     */
    private IQ buildErrorResponse(IQ packet, PacketError.Condition condition, String message) {
        IQ reply = IQ.createResultIQ(packet);
        reply.setChildElement(packet.getChildElement().createCopy());
        final PacketError packetError = new PacketError(condition);
        if (message != null && !message.isEmpty()) {
            packetError.setText(message);
        }
        reply.setError(packetError);
        return reply;
    }

    /**
     * Retrieve messages matching query request from server archive
     * @param queryRequest The request (cannot be null).
     * @return A collection of messages (possibly empty, never null).
     */
    private Collection<ArchivedMessage> retrieveMessages(QueryRequest queryRequest) throws NotFoundException {

        JID withField = null;
        String startField = null;
        String endField = null;
        String textField = null;
        final MonitoringPlugin plugin = (MonitoringPlugin) XMPPServer.getInstance().getPluginManager().getPlugin(MonitoringConstants.NAME);
        final ConversationManager conversationManager = (ConversationManager)plugin.getModule( ConversationManager.class);

        
        DataForm dataForm = queryRequest.getDataForm();
        if(dataForm != null) {
            if(dataForm.getField("with") != null) {
                withField = new JID(dataForm.getField("with").getFirstValue());
            }
            if(dataForm.getField("start") != null) {
                startField = dataForm.getField("start").getFirstValue();
            }
            if(dataForm.getField("end") != null) {
                endField = dataForm.getField("end").getFirstValue();
            }
            if(dataForm.getField("{urn:xmpp:fulltext:0}fulltext") != null) {
                textField = String.join(" ", dataForm.getField("{urn:xmpp:fulltext:0}fulltext").getValues() );
            }

            // issue #81: Silently accept fields used for text search in M-Link and ejabberd's implementation.
            if (textField == null || textField.isEmpty() ) {
                if(dataForm.getField("withtext") != null) { // ejabberd
                    textField = String.join(" ", dataForm.getField("withtext").getValues() );
                }
            }
            if (textField == null || textField.isEmpty() ) {
                if(dataForm.getField("search") != null) { // M-Link
                    textField = String.join(" ", dataForm.getField("search").getValues() );
                }
            }
        }

       try
        {
	        ZonedDateTime nowDate = ZonedDateTime.now();
	        ZonedDateTime newDate = nowDate.minusDays(conversationManager.getMaxRetrievable());
   
	        Date startDate = null;
	        Date endDate = null;
	        try {
	        	
	        	/*
	        	   Client has not provided a start date.
	        	   Check if the server has set a number-of-days limit to the history that clients are allowed retrieve.
	        	        if Yes: use the server-defined limit
	        	        if No: use null for any (more infos: PersistenceManager.findMessages())
	        	*/
	        	if (startField==null)
	        	{
	        		if (conversationManager.getMaxRetrievable()>0)
	        		{
	        			// we have maxRetrievable set
	        			startDate = Date.from(newDate.toInstant());
	        		}
	        		else
	        		{
	        			// we dont have maxRetrievable set
	        			startDate = null;
	        		}
	        	}
	        	else
	        	/*
	        	   Client has provided a start date.
	        	   Check if the server has set a number-of-days limit to the history that clients are allowed retrieve.
	        	   if Yes: check if the client requests data that is older than the server-defined limit
	        	     -- if Yes: use the server-defined limit
	        	     -- if No: use the client start date
	        	   if No: use the client start date
	        	*/
	        	{
	        		if (conversationManager.getMaxRetrievable()>0)
	        		{
	        			// we have maxRetrievable set
	        			ZonedDateTime date = ZonedDateTime.ofInstant(xmppDateTimeFormat.parseString(startField).toInstant(),ZoneId.systemDefault());
	        			if (newDate.isAfter(date))
	        			{
	        				// clients date is chronographically "earlier" than maxRetrievable date
	        				startDate = Date.from(newDate.toInstant());
	        			}
	        			else
	        			{
	        				// clients date is chronographically "alter" than maxRetrievable date
	        				startDate = xmppDateTimeFormat.parseString(startField);
	        			}
	        		}
	        		else
	        		{
	        			// we dont have maxRetrievable set, use clients date
	        			startDate = xmppDateTimeFormat.parseString(startField);
	        		}
	        	}
	        	/* Check if client has set endField
	        	   > yes: use clients end field
	        	   > no : use actual date	        	 
	        	 */
	            if(endField != null) {
	                endDate = xmppDateTimeFormat.parseString(endField);
	            }
	            else
	            {
	            	endDate=new Date();
	            }
	        } catch (ParseException e) {
	            Log.error("An exception has occurred while parsing one of the date fields: ", e);
	        }
	       
	        Collection <ArchivedMessage> result = getPersistenceManager(queryRequest.getArchive()).findMessages(
	                startDate,
	                endDate,
	                queryRequest.getArchive().asBareJID(),
	                withField,
	                textField,
	                queryRequest.getResultSet(),
                	this.usesUniqueAndStableIDs());
	        
	        Log.debug("MAM: found: "+(result!=null?String.valueOf(result.size()):"0 (result==null)")+" items");
	        
	        return result;
        }
        catch ( NotFoundException e )
        {
            throw e; // Retrow exceptions that should result in an IQ error response.
        }
        catch (Exception e)
        {
        	Log.error("An exception has occurred while retrieving messages: ",e);        	
        	return new LinkedList<>();
        }
    }

    /**
     * Defines if the implementation uses XEP-0359-defined 'unique and stable'
     * stanza identifiers. MAM2 introduced a dependency on this new feature.
     *
     * @return true if the implementation uses XEP-0359, otherwise, false.
     */
    abstract boolean usesUniqueAndStableIDs();

    /**
     * Send result packet to client acknowledging query.
     * @param packet Received query packet
     */
    private void sendAcknowledgementResult(IQ packet) {
        IQ result = IQ.createResultIQ(packet);
        router.route(result);
    }

    /**
     * Send final message back to client following query.
     * @param from to respond to
     * @param queryRequest Received query request
     */
    private void sendFinalMessage(JID from, final QueryRequest queryRequest) {

        Message finalMessage = new Message();
        finalMessage.setTo(from);
        Element fin = finalMessage.addChildElement("fin", NAMESPACE);
        if(queryRequest.getQueryid() != null) {
            fin.addAttribute("queryid", queryRequest.getQueryid());
        }

        XmppResultSet resultSet = queryRequest.getResultSet();
        if (resultSet != null) {
            fin.add(resultSet.createResultElement());

            if(resultSet.isComplete()) {
                fin.addAttribute("complete", "true");
            }
        }

        router.route(finalMessage);
    }

    /**
     * Send archived message to requesting client
     * @param from to recieve message
     * @param queryRequest Query request made by client
     * @param archivedMessage Message to send to client
     * @return
     */
    private void sendMessageResult(JID from, QueryRequest queryRequest, ArchivedMessage archivedMessage) {
        String stanzaText = archivedMessage.getStanza();
        if(stanzaText == null || stanzaText.equals("")) {
            // Try creating a fake one from the body.
            if (archivedMessage.getBody() != null && !archivedMessage.getBody().equals("")) {
                final JID to;
                final JID frm;
                if (archivedMessage.getDirection() == ArchivedMessage.Direction.to) {
                    // message sent by the archive owner;
                    to = archivedMessage.getWith();
                    frm = queryRequest.getArchive();
                } else {
                    // message received by the archive owner;
                    to = queryRequest.getArchive();
                    frm = archivedMessage.getWith();
                }
                final boolean isMuc = (to != null &&XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService( to ) != null)
                    || (from != null && XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService( from ) != null);

                stanzaText = String.format("<message from=\"%s\" to=\"%s\" type=\"%s\"><body>%s</body></message>", frm, to, isMuc ? "groupchat" : "chat", archivedMessage.getBody());
                Log.trace( "Reconstructed stanza (only a body was stored): {}", stanzaText );
            } else {
                // Don't send legacy archived messages (that have no stanza)
                return;
            }
        }

        final boolean isMuc = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService( queryRequest.getArchive() ) != null;
        Message messagePacket = new Message();
        messagePacket.setTo(from);
        if (isMuc)
        {
            messagePacket.setFrom( queryRequest.getArchive().asBareJID() );
        }
        Forwarded fwd;

        Document stanza;
        try {
            stanza = DocumentHelper.parseText(stanzaText);
            if ( isMuc ) {
                // XEP-0313 specifies in section 5.1.2 MUC Archives: When sending out the archives to a requesting client, the forwarded stanza MUST NOT have a 'to' attribute.
                final Attribute to = stanza.getRootElement().attribute("to");
                if (to != null) {
                    stanza.getRootElement().remove(to);
                }
            }
            fwd = new Forwarded(stanza.getRootElement(), archivedMessage.getTime(), null);
        } catch (DocumentException e) {
            Log.error("Failed to parse message stanza.", e);
            // If we can't parse stanza then we have no message to send to client, abort
            return;
        }

        messagePacket.addExtension(new Result(fwd, NAMESPACE, queryRequest.getQueryid(), archivedMessage.getId().toString()));
        router.route(messagePacket);
    }

    /**
     * Declare DataForm fields supported by the MAM implementation on this server
     * @param packet Incoming query (form field request) packet
     */
    private IQ buildSupportedFieldsResult(IQ packet) {

        IQ result = IQ.createResultIQ(packet);

        Element query = result.setChildElement("query", NAMESPACE);

        DataForm form = new DataForm(DataForm.Type.form);
        form.addField("FORM_TYPE", null, FormField.Type.hidden);
        form.getField("FORM_TYPE").addValue(NAMESPACE);
        form.addField("with", "Author of message", FormField.Type.jid_single);
        form.addField("start", "Message sent on or after timestamp.", FormField.Type.text_single);
        form.addField("end", "Message sent on or before timestamp.", FormField.Type.text_single);
        form.addField("{urn:xmpp:fulltext:0}fulltext", "Free text search", FormField.Type.text_single);

        query.add(form.getElement());

        return result;
    }

    /**
     * Generates a list of field variable names that are allowed in the dataform of a query. Note that this list should
     * contain <em>all</em> supported fields (even those that are not exposed by {@link #buildSupportedFieldsResult(IQ)}),
     * as the value that's returned is intended to be used to generate errors on unrecognized field vars.
     *
     * @return A list of fields. Never null.
     */
    private List<String> getSupportedFieldVariables() {
        return Arrays.asList( "FORM_TYPE", "with", "start", "end", "{urn:xmpp:fulltext:0}fulltext", "withtext", "search");
    }

    @Override
    public Iterator<String> getFeatures() {
        final List<String> result = new ArrayList<>();
        result.add(NAMESPACE);
        result.add("urn:xmpp:fulltext:0");
        return result.iterator();
    }

    void completeFinElement(QueryRequest queryRequest, Element fin) {
        if(queryRequest.getQueryid() != null) {
            fin.addAttribute("queryid", queryRequest.getQueryid());
        }

        XmppResultSet resultSet = queryRequest.getResultSet();
        if (resultSet != null) {
            fin.add(resultSet.createResultElement());

            if(resultSet.isComplete()) {
                fin.addAttribute("complete", "true");
            }
        }
    }
}
