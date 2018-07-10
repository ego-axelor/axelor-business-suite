package com.axelor.apps.production.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.examples.projectjobscheduling.domain.Allocation;
import org.optaplanner.examples.projectjobscheduling.domain.ExecutionMode;
import org.optaplanner.examples.projectjobscheduling.domain.Job;
import org.optaplanner.examples.projectjobscheduling.domain.JobType;
import org.optaplanner.examples.projectjobscheduling.domain.Project;
import org.optaplanner.examples.projectjobscheduling.domain.ResourceRequirement;
import org.optaplanner.examples.projectjobscheduling.domain.Schedule;
import org.optaplanner.examples.projectjobscheduling.domain.resource.GlobalResource;
import org.optaplanner.examples.projectjobscheduling.domain.resource.LocalResource;
import org.optaplanner.examples.projectjobscheduling.domain.resource.Resource;

import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.ProdProcessLine;
import com.axelor.apps.production.db.WorkCenter;
import com.axelor.apps.production.db.repo.ManufOrderRepository;
import com.axelor.apps.production.db.repo.OperationOrderRepository;
import com.axelor.apps.production.db.repo.WorkCenterRepository;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.apps.tool.date.DurationTool;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.google.common.collect.Lists;
import com.google.inject.persist.Transactional;

public class ManufOrderPlanServiceImpl implements ManufOrderPlanService {

	  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
	  public void optaPlan(ManufOrder manufOrder) throws AxelorException {
	    optaPlan(Lists.newArrayList(manufOrder));
	  }

	  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
	  public void optaPlan(List<ManufOrder> manufOrderList) throws AxelorException {
	    // Build the Solver
	    SolverFactory<Schedule> solverFactory =
	        SolverFactory.createFromXmlResource(
	            "projectjobscheduling/solver/projectJobSchedulingSolverConfig.xml");
	    Solver<Schedule> solver = solverFactory.buildSolver();

	    // Custom Unsolved Job Scheduling
	    Schedule unsolvedJobScheduling = new Schedule();
	    unsolvedJobScheduling.setJobList(new ArrayList<Job>());
	    unsolvedJobScheduling.setProjectList(new ArrayList<Project>());
	    unsolvedJobScheduling.setResourceList(new ArrayList<Resource>());
	    unsolvedJobScheduling.setResourceRequirementList(new ArrayList<ResourceRequirement>());
	    unsolvedJobScheduling.setExecutionModeList(new ArrayList<ExecutionMode>());
	    unsolvedJobScheduling.setAllocationList(new ArrayList<Allocation>());

	    // Create Resources
	    List<WorkCenter> workCenterList = Beans.get(WorkCenterRepository.class).all().fetch();
	    Map<String, Resource> machineCodeToResourceMap = new HashMap<>();
	    for (WorkCenter workCenter : workCenterList) {
	      Resource resource = new GlobalResource();

	      resource.setCapacity(1);
	      long resourceId =
	          unsolvedJobScheduling.getResourceList().size() > 0
	              ? unsolvedJobScheduling
	                      .getResourceList()
	                      .get(unsolvedJobScheduling.getResourceList().size() - 1)
	                      .getId()
	                  + 1
	              : 0;
	          resource.setId(resourceId);

	      machineCodeToResourceMap.put(workCenter.getCode(), resource);

	      unsolvedJobScheduling.getResourceList().add(resource);
	    }

	    Map<Long, ManufOrder> projectIdToManufOrderMap = new HashMap<>();
	    Map<Long, ProdProcessLine> allocationIdToProdProcessLineMap = new HashMap<>();
	    for (ManufOrder manufOrder : manufOrderList) {
	      // Create project
	      Project project =
	          createProject(
	              unsolvedJobScheduling,
	              manufOrder,
	              machineCodeToResourceMap,
	              allocationIdToProdProcessLineMap);
	      projectIdToManufOrderMap.put(project.getId(), manufOrder);
	    }

	    // Solve the problem
	    Schedule solvedJobScheduling = solver.solve(unsolvedJobScheduling);

	    for (ManufOrder manufOrder : manufOrderList) {
	      manufOrder.getOperationOrderList().clear();
	      manufOrder.setStatusSelect(ManufOrderRepository.STATUS_PLANNED);
	      if (manufOrder.getManufOrderSeq() == null)
	        manufOrder.setManufOrderSeq(Beans.get(ManufOrderService.class).getManufOrderSeq());
	      manufOrder.setPlannedStartDateT(null);
	      manufOrder.setPlannedEndDateT(null);
	    }
	    for (Allocation allocation : solvedJobScheduling.getAllocationList()) {
	      OperationOrder operationOrder = new OperationOrder();
	      ProdProcessLine prodProcessLine = allocationIdToProdProcessLineMap.get(allocation.getId());

	      if (prodProcessLine != null) {
	        ManufOrder manufOrder = projectIdToManufOrderMap.get(allocation.getProject().getId());

	        operationOrder.setOperationName(prodProcessLine.getName());
	        LocalDateTime now =
	            Beans.get(AppProductionService.class).getTodayDateTime().toLocalDateTime();

	        LocalDateTime operationOrderPlannedStartDate = now.plusMinutes(allocation.getStartDate());
	        operationOrder.setPlannedStartDateT(operationOrderPlannedStartDate);
	        if (manufOrder.getPlannedStartDateT() == null
	            || manufOrder.getPlannedStartDateT().isAfter(operationOrderPlannedStartDate)) {
	          manufOrder.setPlannedStartDateT(operationOrderPlannedStartDate);
	        }

	        LocalDateTime operationOrderPlannedEndDate = now.plusMinutes(allocation.getEndDate());
	        operationOrder.setPlannedEndDateT(operationOrderPlannedEndDate);
	        if (manufOrder.getPlannedEndDateT() == null
	            || manufOrder.getPlannedEndDateT().isBefore(operationOrderPlannedEndDate)) {
	          manufOrder.setPlannedEndDateT(operationOrderPlannedEndDate);
	        }

	        operationOrder.setPlannedDuration(
	            DurationTool.getSecondsDuration(
	                Duration.between(
	                    operationOrder.getPlannedStartDateT(), operationOrder.getPlannedEndDateT())));

	        operationOrder.setPriority(prodProcessLine.getPriority());
	        operationOrder.setManufOrder(manufOrder);
	        operationOrder.setWorkCenter(prodProcessLine.getWorkCenter());
	        operationOrder.setMachineWorkCenter(prodProcessLine.getWorkCenter());
	        operationOrder.setStatusSelect(OperationOrderRepository.STATUS_PLANNED);
	        operationOrder.setProdProcessLine(prodProcessLine);

	        manufOrder.addOperationOrderListItem(operationOrder);
	      }
	    }
	  }

	  private int getCriticalPathDuration(Project project) {
	    Job sourceJob = null;
	    for (Job job : project.getJobList()) {
	      if (job.getJobType() == JobType.SOURCE) {
	        sourceJob = job;
	        break;
	      }
	    }
	    if (sourceJob != null) {
	      return getCriticalPathDuration(sourceJob);
	    }
	    return 0;
	  }

	  private int getCriticalPathDuration(Job job) {
	    if (job.getJobType() == JobType.SINK) {
	      return 0;
	    } else {
	      int maximumCriticalPathDuration = 0;
	      for (Job successorJob : job.getSuccessorJobList()) {
	        int criticalPathDuration = getCriticalPathDuration(successorJob);
	        if (criticalPathDuration > maximumCriticalPathDuration) {
	          maximumCriticalPathDuration = criticalPathDuration;
	        }
	      }
	      return maximumCriticalPathDuration + maximumExecutionModeDuration(job);
	    }
	  }

	  private int maximumExecutionModeDuration(Job job) {
	    int maximumExecutionModeDuration = 0;
	    if (job.getExecutionModeList() != null) {
	      for (ExecutionMode executionMode : job.getExecutionModeList()) {
	        if (maximumExecutionModeDuration < executionMode.getDuration()) {
	          maximumExecutionModeDuration = executionMode.getDuration();
	        }
	      }
	      return maximumExecutionModeDuration;
	    }
	    return 0;
	  }

	  private Project createProject(
	      Schedule unsolvedJobScheduling,
	      ManufOrder manufOrder,
	      Map<String, Resource> machineCodeToResourceMap,
	      Map<Long, ProdProcessLine> allocationIdToProdProcessLineMap) {

	    List<ProdProcessLine> prodProcessLineList =
	        manufOrder.getProdProcess().getProdProcessLineList();
	    Map<Integer, List<ProdProcessLine>> priorityToProdProcessLineMap = new HashMap<>();
	    for (ProdProcessLine prodProcessLine : prodProcessLineList) {
	      int priority = prodProcessLine.getPriority();
	      if (!priorityToProdProcessLineMap.containsKey(priority)) {
	        priorityToProdProcessLineMap.put(priority, new ArrayList<ProdProcessLine>());
	      }
	      priorityToProdProcessLineMap.get(priority).add(prodProcessLine);
	    }
	    List<Integer> sortedPriorityList =
	        new ArrayList<Integer>(new TreeSet<Integer>(priorityToProdProcessLineMap.keySet()));
	    Map<Integer, ArrayList<Job>> priorityToJobMap = new HashMap<>();
	    Map<Integer, ArrayList<Allocation>> priorityToAllocationMap = new HashMap<>();
	    for (Integer priority : sortedPriorityList) {
	      priorityToJobMap.put(priority, new ArrayList<Job>());
	      priorityToAllocationMap.put(priority, new ArrayList<Allocation>());
	    }

	    Project project = new Project();
	    long projectId =
	        unsolvedJobScheduling.getProjectList().size() > 0
	            ? unsolvedJobScheduling
	                    .getProjectList()
	                    .get(unsolvedJobScheduling.getProjectList().size() - 1)
	                    .getId()
	                + 1
	            : 0;
	    project.setId(projectId);
	    project.setJobList(new ArrayList<Job>());
	    project.setLocalResourceList(new ArrayList<LocalResource>());
	    project.setReleaseDate(0);
	    project.setCriticalPathDuration(getCriticalPathDuration(project));
	    unsolvedJobScheduling.getProjectList().add(project);

	    Job sourceJob = new Job();
	    long sourceJobId =
	        unsolvedJobScheduling.getJobList().size() > 0
	            ? unsolvedJobScheduling
	                    .getJobList()
	                    .get(unsolvedJobScheduling.getJobList().size() - 1)
	                    .getId()
	                + 1
	            : 0;
	    sourceJob.setId(sourceJobId);
	    sourceJob.setProject(project);
	    sourceJob.setJobType(JobType.SOURCE);
	    sourceJob.setSuccessorJobList(priorityToJobMap.get(sortedPriorityList.get(0)));
	    project.getJobList().add(sourceJob);
	    unsolvedJobScheduling.getJobList().add(sourceJob);

	    Allocation sourceAllocation = new Allocation();
	    sourceAllocation.setPredecessorsDoneDate(0);
	    sourceAllocation.setId((long) (projectId * 100));
	    sourceAllocation.setSuccessorAllocationList(
	        priorityToAllocationMap.get(sortedPriorityList.get(0)));
	    sourceAllocation.setJob(sourceJob);
	    unsolvedJobScheduling.getAllocationList().add(sourceAllocation);
	    List<Allocation> sourceAllocationList = new ArrayList<>();
	    sourceAllocationList.add(sourceAllocation);

	    Job sinkJob = new Job();
	    long sinkJobId =
	        unsolvedJobScheduling.getJobList().size() > 0
	            ? unsolvedJobScheduling
	                    .getJobList()
	                    .get(unsolvedJobScheduling.getJobList().size() - 1)
	                    .getId()
	                + 1
	            : 0;
	    sinkJob.setId(sinkJobId);
	    sinkJob.setProject(project);
	    sinkJob.setJobType(JobType.SINK);
	    project.getJobList().add(sinkJob);
	    unsolvedJobScheduling.getJobList().add(sinkJob);
	    List<Job> sinkJobList = new ArrayList<>();
	    sinkJobList.add(sinkJob);

	    Allocation sinkAllocation = new Allocation();
	    sinkAllocation.setPredecessorsDoneDate(0);
	    sinkAllocation.setId((long) (projectId * 100 + prodProcessLineList.size() + 1));
	    sinkAllocation.setPredecessorAllocationList(
	        priorityToAllocationMap.get(sortedPriorityList.get(sortedPriorityList.size() - 1)));
	    sinkAllocation.setSuccessorAllocationList(new ArrayList<Allocation>());
	    sinkAllocation.setJob(sinkJob);
	    List<Allocation> sinkAllocationList = new ArrayList<>();
	    sinkAllocationList.add(sinkAllocation);

	    int allocationIdx = 0;
	    for (int priorityIdx = 0; priorityIdx < sortedPriorityList.size(); priorityIdx++) {
	      int priority = sortedPriorityList.get(priorityIdx);
	      for (ProdProcessLine prodProcessLine : priorityToProdProcessLineMap.get(priority)) {
	        // Job
	        Job job = new Job();
	        long jobId =
	            unsolvedJobScheduling.getJobList().size() > 0
	                ? unsolvedJobScheduling
	                        .getJobList()
	                        .get(unsolvedJobScheduling.getJobList().size() - 1)
	                        .getId()
	                    + 1
	                : 0;
	        job.setId(jobId);
	        job.setExecutionModeList(new ArrayList<ExecutionMode>());
	        job.setJobType(JobType.STANDARD);
	        job.setProject(project);
	        if (priorityIdx < sortedPriorityList.size() - 1) {
	          job.setSuccessorJobList(priorityToJobMap.get(sortedPriorityList.get(priorityIdx + 1)));
	        } else {
	          job.setSuccessorJobList(sinkJobList);
	        }

	        unsolvedJobScheduling.getJobList().add(job);

	        priorityToJobMap.get(priority).add(job);

	        project.getJobList().add(job);

	        // Execution Mode
	        ExecutionMode executionMode = new ExecutionMode();
	        long executionModeId =
	            unsolvedJobScheduling.getExecutionModeList().size() > 0
	                ? unsolvedJobScheduling
	                        .getExecutionModeList()
	                        .get(unsolvedJobScheduling.getExecutionModeList().size() - 1)
	                        .getId()
	                    + 1
	                : 0;
	        executionMode.setId(executionModeId);
	        executionMode.setJob(job);
	        executionMode.setResourceRequirementList(new ArrayList<ResourceRequirement>());
	        long duration = 0;
	        if (prodProcessLine.getWorkCenter().getWorkCenterTypeSelect() != 1) {
	          duration =
	              (long)
	                  (prodProcessLine.getWorkCenter().getDurationPerCycle()
	                      * Math.ceil(
	                          (float) manufOrder.getQty().intValue()
	                              / prodProcessLine
	                                  .getWorkCenter()
	                                  .getMaxCapacityPerCycle()
	                                  .intValue()));
	        } else if (prodProcessLine.getWorkCenter().getWorkCenterTypeSelect() == 1) {
	          duration =
	              prodProcessLine.getWorkCenter().getProdHumanResourceList().get(0).getDuration()
	                  * manufOrder.getQty().intValue();
	        }
	        executionMode.setDuration((int) duration / 60);

	        unsolvedJobScheduling.getExecutionModeList().add(executionMode);

	        job.getExecutionModeList().add(executionMode);

	        // Resource Requirement
	        ResourceRequirement resourceRequirement = new ResourceRequirement();
	        long resourceRequirementId =
	            unsolvedJobScheduling.getResourceRequirementList().size() > 0
	                ? unsolvedJobScheduling
	                        .getResourceRequirementList()
	                        .get(unsolvedJobScheduling.getResourceRequirementList().size() - 1)
	                        .getId()
	                    + 1
	                : 0;
	        resourceRequirement.setId(resourceRequirementId);
	        resourceRequirement.setExecutionMode(executionMode);
	        Resource resource = machineCodeToResourceMap.get(prodProcessLine.getWorkCenter().getCode());
	        resourceRequirement.setResource(resource);
	        resourceRequirement.setRequirement(1);
	        executionMode.getResourceRequirementList().add(resourceRequirement);

	        unsolvedJobScheduling.getResourceRequirementList().add(resourceRequirement);

	        // Allocation
	        Allocation allocation = new Allocation();
	        Long allocationId = (long) (projectId * 100 + (allocationIdx + 1));
	        allocation.setId(allocationId);
	        allocationIdx++;
	        allocation.setJob(job);
	        List<Allocation> predecessorAllocationList =
	            priorityIdx > 0
	                ? priorityToAllocationMap.get(sortedPriorityList.get(priorityIdx - 1))
	                : sourceAllocationList;
	        allocation.setPredecessorAllocationList(predecessorAllocationList);
	        List<Allocation> successorAllocationList =
	            priorityIdx < sortedPriorityList.size() - 1
	                ? priorityToAllocationMap.get(sortedPriorityList.get(priorityIdx + 1))
	                : sinkAllocationList;
	        allocation.setSuccessorAllocationList(successorAllocationList);
	        allocationIdToProdProcessLineMap.put(allocationId, prodProcessLine);
	        allocation.setPredecessorsDoneDate(0);
	        allocation.setSourceAllocation(sourceAllocation);
	        allocation.setSinkAllocation(sinkAllocation);
	        allocation.setPredecessorsDoneDate(0);

	        unsolvedJobScheduling.getAllocationList().add(allocation);

	        priorityToAllocationMap.get(priority).add(allocation);
	      }
	    }

	    unsolvedJobScheduling.getAllocationList().add(sinkAllocation);

	    return project;
	  }
}
