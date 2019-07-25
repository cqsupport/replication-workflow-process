package com.adobe.support.replication.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.apache.sling.jcr.api.SlingRepository;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.collection.ResourceCollection;
import com.adobe.granite.workflow.collection.ResourceCollectionManager;
import com.adobe.support.replication.ActivationReferenceSearch;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;
import com.day.cq.wcm.workflow.api.WcmWorkflowService;
import com.day.cq.wcm.workflow.process.ResourceCollectionHelper;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.HistoryItem;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.metadata.MetaDataMap;
import com.day.cq.workflow.model.WorkflowNode;

/**
 * This abstract <code>ReplicatePageProcess</code> class serves as the basis for
 * all replication specific <code>JavaProcess</code> classes, like activate,
 * deactivate...<br>
 * This Process starts the Replicator with the type provided by the implementing
 * Classes
 * {@link com.day.cq.wcm.workflow.process.ReplicatePageProcess#getReplicationType()
 * getReblicationType()-method} of payloads of type <i>path</i> or <i>uuid</i><br>
 * This process checks permissions.<br>
 * In case the {@link com.day.cq.workflow.WorkflowSession Session} starting the
 * process is lacking the Privilege to replicate, an event of this topic
 * <code>{@value com.day.cq.wcm.workflow.api.WcmWorkflowService#EVENT_TOPIC}</code>
 * is send out. Listeners to this topic, may handle this situation.
 * <b>Configuration</b>
 * This process supports the following configuration arguments:
 * <dl>
 * <dt>replicateAsParticipant</dt>
 * <dd>Boolean flag indicating if the replication should be performed under the context of the latest participant, Default is <code>false</code>,
 * meaning the replication is performed as workflow-session-service-user. This process supports this configuration either as a dedicated process
 * arguments or as part of the generic PROCESS_ARGS argument. If set to <code>true</code> the workflow model must have
 * a participant or dynamic participant step modeled ahead this replication process, to determine the participant. If no
 * participant can be determined, it falls back to workflow-session-service-user.
 * </dd>
 * </dl>
 *
 */
@Component(componentAbstract = true)
public abstract class ReplicatePageProcess implements WorkflowProcess {
    /**
     * the logger
     */
    private static final Logger log = LoggerFactory.getLogger(ReplicatePageProcess.class);

    public static final String TYPE_JCR_PATH = "JCR_PATH";
    public static final String TYPE_JCR_UUID = "JCR_UUID";

    private static final String WCM_WORKFLOW_SERVICE = "wcm-workflow-service";

    @Reference
    protected Replicator replicator;

    @Reference
    protected EventAdmin eventAdmin;

    @Reference
    protected ResourceCollectionManager rcManager;

    @Reference
    protected SlingRepository repository;
    
    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    ActivationReferenceSearch activationReferenceSearch;
    
    private enum Arguments {
        PROCESS_ARGS("PROCESS_ARGS"), REPLICATE_AS_PARTICIPANT("replicateAsParticipant");

        private String argumentName;

        Arguments(String argumentName) {
            this.argumentName = argumentName;
        }

        public String getArgumentName() {
            return this.argumentName;
        }
    }

    /**
     * @see com.day.cq.workflow.exec.WorkflowProcess#execute(com.day.cq.workflow.exec.WorkItem,
     *      com.day.cq.workflow.WorkflowSession,
     *      com.day.cq.workflow.metadata.MetaDataMap)
     */
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args) throws WorkflowException {

        Session participantSession = null;
        Session replicationSession = null;
        Session serviceSession = null;
        ResourceResolver resolver = null;
        try {
            Session session = workflowSession.getSession();

            if (replicateAsParticipant(args)) {
                String approverId = resolveParticipantId(workItem, workflowSession);
                if (approverId != null) {
                    participantSession = getParticipantSession(approverId, workflowSession);
                }
            }

            if (participantSession != null) {
                replicationSession = participantSession;
            } else {
                replicationSession = session;
            }

            WorkflowData data = workItem.getWorkflowData();
            String path = null;
            String type = data.getPayloadType();

            if (type.equals(TYPE_JCR_PATH) && data.getPayload() != null) {
                String payloadData = (String) data.getPayload();
                if (session.itemExists(payloadData)) {
                    path = payloadData;
                }
            } else if (data.getPayload() != null && type.equals(TYPE_JCR_UUID)) {
                Node node = session.getNodeByUUID((String) data.getPayload());
                path = node.getPath();
            }

            MetaDataMap metaDataMap = data.getMetaDataMap();
            Map<String, String> versionMap = new HashMap<String, String>();

            if (metaDataMap.containsKey("versions")) {
                JSONObject versionJs = new JSONObject(data.getMetaDataMap().get("versions", String.class));
                Iterator iterator = versionJs.keys();

                while (iterator.hasNext()) {
                    String key = (String)iterator.next();
                    versionMap.put(key, (String)versionJs.get(key));
                }
            }

            if (path != null) {
            	final Map<String, Object> authInfo = Collections.singletonMap(
                        ResourceResolverFactory.SUBSERVICE,
                        (Object) WCM_WORKFLOW_SERVICE);
            	resolver = resolverFactory.getServiceResourceResolver(authInfo);

                serviceSession = resolver.adaptTo(Session.class);
                // check for resource collection
                log.info(serviceSession.getUserID());
                List<ResourceCollection> rcCollections = rcManager.getCollectionsForNode((Node) serviceSession.getItem(path));

                List<String> paths = new ArrayList<String>();
                paths.addAll(activationReferenceSearch.search(new String[] {path}, resolver));

                // get list of paths to replicate (no resource collection: size
                // == 1
                // otherwise size >= 1
                List<String> rcPaths = ResourceCollectionHelper.getPaths(path, rcCollections);
                if(rcPaths != null && rcPaths.size() > 0) {
                	paths.addAll(rcPaths);
                }
                		                		
                for (String aPath : paths) {
                    if (canReplicate(replicationSession, aPath)) {
                        ReplicationOptions opts = new ReplicationOptions();
                        String versionLabel = getVersionLabel(aPath, versionMap);

                        // if a version's label exist pass it to the replicator
                        if (StringUtils.isNotEmpty(versionLabel)) {
                            // Set the local revision label
                            opts.setRevision(versionLabel);
                        }
                        opts = prepareOptions(opts);
                        replicator.replicate(replicationSession, getReplicationType(), aPath, opts);
                    } else {
                        // request for "replication action"
                        log.debug(session.getUserID() + " is not allowed to replicate " + "this page/asset " + aPath
                                + ". Issuing request for 'replication");
                        final Dictionary<String, Object> properties = new Hashtable<String, Object>();
                        properties.put("path", aPath);
                        properties.put("replicationType", getReplicationType());
                        properties.put("userId", session.getUserID());
                        Event event = new Event(WcmWorkflowService.EVENT_TOPIC, properties);
                        eventAdmin.sendEvent(event);
                    }
                }
            } else {
                log.warn("Cannot activate page or asset because path is null for this " + "workitem: "
                        + workItem.toString());
            }
        } catch (RepositoryException e) {
            throw new WorkflowException(e);
        } catch (ReplicationException e) {
            throw new WorkflowException(e);
        } catch (JSONException e) {
            throw new WorkflowException(e);
        } catch (LoginException e) {
			throw new WorkflowException(e);
		} finally {
            if (participantSession != null && participantSession.isLive()) {
                participantSession.logout();
                participantSession = null;
            }
            if(resolver != null && resolver.isLive()) {
            	resolver.close();
            }
            if(serviceSession != null && serviceSession.isLive()){
                serviceSession.logout();
                serviceSession = null;
            }
        }
    }

    /**
     * Returns the latest version for the given resource path
     *
     * @param path Path to the resource
     * @param versionMap Map of available version labels
     * @return
     */
    private String getVersionLabel(String path, Map<String, String> versionMap) {
        if (StringUtils.isEmpty(path)) {
            return null;
        }

        if (versionMap.containsKey(path)) {
            return versionMap.get(path);
        }

        if (!path.endsWith("/" + JcrConstants.JCR_CONTENT)) {
            path += "/" + JcrConstants.JCR_CONTENT;
        }

        return versionMap.get(path);
    }

    /**
     * Determine the replication mode from the arguments map.
     *
     * @param args The process arguments
     * @return Depending on the process arguments <code>true</code> if the replication is supposed to take place under
     *         the participants context, <code>false</code> otherwise.
     */
    private boolean replicateAsParticipant(MetaDataMap args) {
        String processArgs = args.get(Arguments.PROCESS_ARGS.getArgumentName(), String.class);
        if (processArgs != null && !processArgs.equals("")) {
            String[] arguments = processArgs.split(",");
            for (String argument : arguments) {
                String[] split = argument.split("=");
                if (split.length == 2) {
                    String key = split[0];
                    String value = split[1];
                    if (key.equalsIgnoreCase(Arguments.REPLICATE_AS_PARTICIPANT.getArgumentName())) {
                        return Boolean.parseBoolean(value);
                    }
                }
            }
            return false;
        } else {
            return args.get(Arguments.REPLICATE_AS_PARTICIPANT.getArgumentName(), Boolean.FALSE);
        }
    }

    /**
     * Get a session for the given approver.
     *
     * @param participantId
     * @param workflowSession
     * @return The approver's session or <code>null</code> in case of repository exceptions.
     */

    private Session getParticipantSession(String participantId, WorkflowSession workflowSession) {
        try {
            return this.repository.impersonateFromService(WCM_WORKFLOW_SERVICE,new SimpleCredentials(participantId,new char[0]),null);
        } catch (Exception e) {
            log.warn(e.getMessage());
            return null;
        }
    }

    /**
     * Travael up the session's history to find the latest participant step or dynamic participant step and use it's
     * current assignee as approver.
     *
     * @param workItem
     * @param workflowSession
     * @return The approver's id of the latest participant/dynamic participant step in the history. In case there is no
     *         participant step, <code>null</code> is returned.
     */
    private String resolveParticipantId(WorkItem workItem, WorkflowSession workflowSession) {
        List<HistoryItem> history = new ArrayList<HistoryItem>();
        try {
            history = workflowSession.getHistory(workItem.getWorkflow());
            for (int index = history.size() - 1; index >= 0; index--) {
                HistoryItem previous = history.get(index);
                String type = previous.getWorkItem().getNode().getType();
                if (type != null && (type.equals(WorkflowNode.TYPE_PARTICIPANT) || type.equals(WorkflowNode.TYPE_DYNAMIC_PARTICIPANT))) {
                    return previous.getUserId();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn(e.getMessage());
            return null;
        }
    }

    /**
     * Specifies the <code>{@link ReplicationActionType}</code> for which this
     * class is designed for.
     *
     * @return <code>{@link ReplicationActionType}</code>
     */
    public abstract ReplicationActionType getReplicationType();

    /**
     * Allows subclasses to mangle with the replication options.
     * @param opts Options for replication
     * @return the options or <code>null</code>
     */
    protected ReplicationOptions prepareOptions(ReplicationOptions opts) {
        return opts;
    }

    protected boolean canReplicate(Session session, String path) throws AccessDeniedException {
        try {
            AccessControlManager acMgr = session.getAccessControlManager();
            return acMgr.hasPrivileges(path, new Privilege[]{acMgr.privilegeFromName(Replicator.REPLICATE_PRIVILEGE)});
        } catch (RepositoryException e) {
            return false;
        }
    }

    // ---------- SCR Integration ----------------------------------------------
}