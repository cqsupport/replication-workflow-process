package com.adobe.support.replication;


import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;

import com.adobe.support.replication.impl.ReplicatePageProcess;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.workflow.exec.WorkflowProcess;

/**
 * <code>ActivatePageProcess</code>
 * Process for Replications of type <i>activate</i>.
 *
 * @see ReplicatePageProcess
 */
@Component
@Service(WorkflowProcess.class)
@Property(name = "process.label", value = "Activate Page w/ References")
public class ActivatePageProcess extends ReplicatePageProcess {

    public ReplicationActionType getReplicationType() {
        return ReplicationActionType.ACTIVATE;
    }
}