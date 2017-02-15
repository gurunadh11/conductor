/**
 * 
 */
package com.netflix.conductor.core.events;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.common.metadata.events.EventExecution;
import com.netflix.conductor.common.metadata.events.EventExecution.Status;
import com.netflix.conductor.common.metadata.events.EventHandler.Action;
import com.netflix.conductor.common.metadata.events.EventHandler.StartWorkflow;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.events.queue.ObservableQueue;
import com.netflix.conductor.core.events.queue.dyno.DynoEventQueueProvider;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.service.ExecutionService;

import rx.annotations.Beta;

/**
 * @author Viren
 * Action Processor subscribes to the Event Actions queue and processes the actions (e.g. start workflow etc)
 * <p><font color=red>Warning</font> This is a work in progress and may be changed in future.  Not ready for production yet.
 */
@Singleton
@Beta
public class ActionProcessor {

	private static Logger logger = LoggerFactory.getLogger(EventProcessor.class);
	
	private ObjectMapper om;
	
	private WorkflowExecutor executor;
	
	private ExecutionService executionService;
	
	static String queueName = "_eventActions";
	
	@Inject
	public ActionProcessor(DynoEventQueueProvider queueProvider, WorkflowExecutor executor, ExecutionService executionService, ObjectMapper om) {
		this.executor = executor;
		this.executionService = executionService;
		this.om = om;
		ObservableQueue actionQueue = queueProvider.getQueue(queueName);
		actionQueue.observe().subscribe((Message msg) -> onMessage(actionQueue, msg));
	}

	static String queueName() {
		return queueName;
	}
	private void onMessage(ObservableQueue queue, Message msg) {
		
		EventExecution ee = new EventExecution();
		ee.setCreated(System.currentTimeMillis());
		
		try {
			
			logger.info("Got Message : {}", msg.getPayload());
			
			
			String payload = msg.getPayload();
			Action action = om.readValue(payload, Action.class);
			
			ee.setAction(action.getAction());
			ee.setEvent(action.getEvent());
			ee.setName(action.getHandlerName());

			switch(action.getAction()) {
				case start_workflow:					
					Map<String, Object> op = startWorkflow(action);
					ee.getOutput().putAll(op);
					ee.setStatus(Status.IN_PROGRESS);
					break;
			}
			queue.ack(Arrays.asList(msg));
			return;
		}catch(Exception e) {
			ee.setStatus(Status.IN_PROGRESS);
			logger.error(e.getMessage(), e);
		}
		
		executionService.addEventExecution(ee);
	}

	private Map<String, Object> startWorkflow(Action action) throws Exception {
		StartWorkflow params = action.getStart_workflow();
		Map<String, Object> op = new HashMap<>();
		try {
			
			String id = executor.startWorkflow(params.getName(), params.getVersion(), params.getCorrelationId(), params.getInput());
			op.put("workflowId", id);
			
		}catch(Exception e) {
			logger.error(e.getMessage(), e);
			op.put("error", e.getMessage());
		}
		
		return op;
	}
}