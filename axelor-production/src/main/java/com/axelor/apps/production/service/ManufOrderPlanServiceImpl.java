package com.axelor.apps.production.service;

import com.axelor.apps.base.db.repo.AppProductionRepository;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.WorkCenter;
import com.axelor.apps.production.db.repo.OperationOrderRepository;
import com.axelor.apps.production.db.repo.WorkCenterRepository;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.apps.tool.date.DurationTool;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.collections.CollectionUtils;
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

public class ManufOrderPlanServiceImpl implements ManufOrderPlanService {

  protected UnitConversionService unitConversionService;

  @Inject
  public ManufOrderPlanServiceImpl(UnitConversionService unitConversionService) {
    this.unitConversionService = unitConversionService;
  }

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
    Schedule unsolvedJobScheduling = getInitializedSchedule();

    // Create Resources
    Map<String, Resource> machineCodeToResourceMap = new HashMap<>();
    createResources(unsolvedJobScheduling, machineCodeToResourceMap);
    
    // Get optaplanner granularity
    Integer granularity;
    LocalDateTime now = Beans.get(AppProductionService.class).getTodayDateTime().toLocalDateTime();
    switch (Beans.get(AppProductionRepository.class).all().fetchOne().getSchedulingGranularity()) {
      case (2):
        granularity = 1800;
        System.out.println(now);
        now = now.truncatedTo(ChronoUnit.HALF_DAYS);
        System.out.println(now);
        break;
      case (3):
        granularity = 3600;
        System.out.println(now);
        now = now.truncatedTo(ChronoUnit.HOURS);
        System.out.println(now);
        break;
      case (4):
        granularity = 86400;
        now = now.truncatedTo(ChronoUnit.DAYS);
        break;
      default:
        granularity = 60;
        now = now.truncatedTo(ChronoUnit.MINUTES);
        break;
    }
    

    Map<Long, ManufOrder> projectIdToManufOrderMap = new HashMap<>();
    Map<Long, OperationOrder> allocationIdToOperationOrderMap = new HashMap<>();
    for (ManufOrder manufOrder : manufOrderList) {
      // Create project
      Project project =
          createProject(
              unsolvedJobScheduling,
              manufOrder,
              machineCodeToResourceMap,
              allocationIdToOperationOrderMap,
              now,
              granularity);
      projectIdToManufOrderMap.put(project.getId(), manufOrder);
    }

    // Solve the problem
    Schedule solvedJobScheduling = solver.solve(unsolvedJobScheduling);

    for (Allocation allocation : solvedJobScheduling.getAllocationList()) {
      OperationOrder operationOrder = allocationIdToOperationOrderMap.get(allocation.getId());

      if (operationOrder != null) {
        planOperationOrder(operationOrder, allocation, granularity, now, projectIdToManufOrderMap);
      }
    }
  }

  private Schedule getInitializedSchedule() {
      Schedule unsolvedJobScheduling = new Schedule();

      unsolvedJobScheduling.setJobList(new ArrayList<Job>());
      unsolvedJobScheduling.setProjectList(new ArrayList<Project>());
      unsolvedJobScheduling.setResourceList(new ArrayList<Resource>());
      unsolvedJobScheduling.setResourceRequirementList(new ArrayList<ResourceRequirement>());
      unsolvedJobScheduling.setExecutionModeList(new ArrayList<ExecutionMode>());
      unsolvedJobScheduling.setAllocationList(new ArrayList<Allocation>());

      return unsolvedJobScheduling;
  }

  private void createResources(Schedule unsolvedJobScheduling, Map<String, Resource> machineCodeToResourceMap){
      List<Resource> resourceList = new ArrayList<Resource>();
      
      List<WorkCenter> workCenterList = Beans.get(WorkCenterRepository.class).all().fetch();
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
    
        resourceList.add(resource);
        
        unsolvedJobScheduling.getResourceList().add(resource);
      }
  }

  private void planOperationOrder(OperationOrder operationOrder, Allocation allocation, Integer granularity, LocalDateTime now, Map<Long, ManufOrder> projectIdToManufOrderMap) throws AxelorException {
    if (CollectionUtils.isEmpty(operationOrder.getToConsumeProdProductList())) {
      Beans.get(OperationOrderService.class).createToConsumeProdProductList(operationOrder);
    }

    LocalDateTime operationOrderPlannedStartDate =
        now.plusSeconds(allocation.getStartDate() * granularity);
    operationOrder.setPlannedStartDateT(operationOrderPlannedStartDate);

    LocalDateTime operationOrderPlannedEndDate =
        now.plusSeconds(allocation.getEndDate() * granularity);
    operationOrder.setPlannedEndDateT(operationOrderPlannedEndDate);

    operationOrder.setPlannedDuration(
        DurationTool.getSecondsDuration(
            Duration.between(
                operationOrder.getPlannedStartDateT(), operationOrder.getPlannedEndDateT())));

    ManufOrder manufOrder = projectIdToManufOrderMap.get(allocation.getProject().getId());
    if (manufOrder == null || manufOrder.getIsConsProOnOperation()) {
      Beans.get(OperationOrderStockMoveService.class).createToConsumeStockMove(operationOrder);
    }

    operationOrder.setStatusSelect(OperationOrderRepository.STATUS_PLANNED);
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

  private Project initializeProject(Schedule unsolvedJobScheduling) {
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
    
    return project;
  }

  private Map<Integer, List<OperationOrder>> getPriorityToOperationOrderListMap(ManufOrder manufOrder){
      Map<Integer, List<OperationOrder>> priorityToOperationOrderListMap = new HashMap<>();
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        int priority = operationOrder.getPriority();
        if (!priorityToOperationOrderListMap.containsKey(priority)) {
            priorityToOperationOrderListMap.put(priority, new ArrayList<OperationOrder>());
        }
        priorityToOperationOrderListMap.get(priority).add(operationOrder);
      }
      
      return priorityToOperationOrderListMap;
  }

  private Map<Integer, ArrayList<Job>> initializePriorityToJobListMap(List<Integer> sortedPriorityList) {
      Map<Integer, ArrayList<Job>> priorityToJobListMap = new HashMap<>();
      
      for (Integer priority : sortedPriorityList) {
        priorityToJobListMap.put(priority, new ArrayList<Job>());
      }
      
      return priorityToJobListMap;
  }

  private Map<Integer, ArrayList<Allocation>> initializePriorityToAllocationListMap(List<Integer> sortedPriorityList) {
      Map<Integer, ArrayList<Allocation>> priorityToAllocationListMap = new HashMap<>();
      
      for (Integer priority : sortedPriorityList) {
        priorityToAllocationListMap.put(priority, new ArrayList<Allocation>());
      }
      
      return priorityToAllocationListMap;
  }

  private Job getSourceJob(Schedule unsolvedJobScheduling, Project project, Map<Integer, ArrayList<Job>> priorityToJobListMap, List<Integer> sortedPriorityList) {
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
      sourceJob.setSuccessorJobList(priorityToJobListMap.get(sortedPriorityList.get(0)));
      
      return sourceJob;
  }

  private Job getSinkJob(Schedule unsolvedJobScheduling, Project project) {
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
      
      return sinkJob;
  }

  private Allocation getSourceAllocation(Project project, Map<Integer, ArrayList<Allocation>> priorityToAllocationListMap, List<Integer> sortedPriorityList, Job sourceJob) {
      Allocation sourceAllocation = new Allocation();
      
      sourceAllocation.setPredecessorsDoneDate(0);
      sourceAllocation.setId((long) (project.getId() * 100));
      sourceAllocation.setSuccessorAllocationList(
  	    priorityToAllocationListMap.get(sortedPriorityList.get(0)));
      sourceAllocation.setJob(sourceJob);
      
      return sourceAllocation;
  }

  private Allocation getSinkAllocation(Project project, Map<Integer, ArrayList<Allocation>> priorityToAllocationListMap, List<Integer> sortedPriorityList, Job sinkJob, ManufOrder manufOrder) {
      Allocation sinkAllocation = new Allocation();
      
      sinkAllocation.setPredecessorsDoneDate(0);
      sinkAllocation.setId((long) (project.getId() * 100 + manufOrder.getOperationOrderList().size() + 1));
      sinkAllocation.setPredecessorAllocationList(
  	    priorityToAllocationListMap.get(sortedPriorityList.get(sortedPriorityList.size() - 1)));
      sinkAllocation.setSuccessorAllocationList(new ArrayList<Allocation>());
      sinkAllocation.setJob(sinkJob);
      
      return sinkAllocation;
  }

  private Job getJob(Schedule unsolvedJobScheduling, Project project, Integer priorityIdx, List<Integer> sortedPriorityList, Map<Integer, ArrayList<Job>> priorityToJobListMap, List<Job> sinkJobList) {
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
        job.setSuccessorJobList(priorityToJobListMap.get(sortedPriorityList.get(priorityIdx + 1)));
      } else {
        job.setSuccessorJobList(sinkJobList);
      }
      
      return job;
  }

  private ExecutionMode getExecutionMode(Schedule unsolvedJobScheduling, Job job, OperationOrder operationOrder, ManufOrder manufOrder, Integer granularity) {
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
      if (operationOrder.getWorkCenter().getWorkCenterTypeSelect() != 1) {
        duration =
            (long)
                (operationOrder.getWorkCenter().getDurationPerCycle()
                    * Math.ceil(
                        (float) manufOrder.getQty().intValue()
                            / operationOrder
                                .getWorkCenter()
                                .getMaxCapacityPerCycle()
                                .intValue()));
      } else if (operationOrder.getWorkCenter().getWorkCenterTypeSelect() == 1) {
        duration =
            operationOrder.getWorkCenter().getProdHumanResourceList().get(0).getDuration()
                * manufOrder.getQty().intValue();
      }
      executionMode.setDuration((int) Math.ceil(((double) duration) / granularity));
      
      return executionMode;
  }

  private ResourceRequirement getResourceRequirement(Schedule unsolvedJobScheduling, ExecutionMode executionMode, OperationOrder operationOrder, Map<String, Resource> machineCodeToResourceMap) {
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
      Resource resource = machineCodeToResourceMap.get(operationOrder.getWorkCenter().getCode());
      resourceRequirement.setResource(resource);
      resourceRequirement.setRequirement(1);
      
      return resourceRequirement;
  }

  private Allocation getAllocation(Project project, Integer allocationIdx, Job job, Integer priorityIdx, Map<Integer, ArrayList<Allocation>> priorityToAllocationListMap, Allocation sourceAllocation, Allocation sinkAllocation, List<Integer> sortedPriorityList, List<Allocation> sourceAllocationList, List<Allocation> sinkAllocationList, Map<Long, OperationOrder> allocationIdToOperationOrderMap, OperationOrder operationOrder) {
      Allocation allocation = new Allocation();
      
      Long allocationId = (long) (project.getId() * 100 + (allocationIdx + 1));
      allocation.setId(allocationId);
      allocation.setJob(job);
      List<Allocation> predecessorAllocationList =
          priorityIdx > 0
              ? priorityToAllocationListMap.get(sortedPriorityList.get(priorityIdx - 1))
              : sourceAllocationList;
      allocation.setPredecessorAllocationList(predecessorAllocationList);
      List<Allocation> successorAllocationList =
          priorityIdx < sortedPriorityList.size() - 1
              ? priorityToAllocationListMap.get(sortedPriorityList.get(priorityIdx + 1))
              : sinkAllocationList;
      allocation.setSuccessorAllocationList(successorAllocationList);
      allocationIdToOperationOrderMap.put(allocationId, operationOrder);
      allocation.setPredecessorsDoneDate(0);
      allocation.setSourceAllocation(sourceAllocation);
      allocation.setSinkAllocation(sinkAllocation);
      allocation.setPredecessorsDoneDate(0);
      
      return allocation;
  }

  private Project createProject(
      Schedule unsolvedJobScheduling,
      ManufOrder manufOrder,
      Map<String, Resource> machineCodeToResourceMap,
      Map<Long, OperationOrder> allocationIdToOperationOrderMap,
      LocalDateTime now,
      Integer granularity) {

    Map<Integer, List<OperationOrder>> priorityToOperationOrderListMap = getPriorityToOperationOrderListMap(manufOrder);
    
    List<Integer> sortedPriorityList =
        new ArrayList<Integer>(new TreeSet<Integer>(priorityToOperationOrderListMap.keySet()));
    
    Map<Integer, ArrayList<Job>> priorityToJobListMap = initializePriorityToJobListMap(sortedPriorityList);
    Map<Integer, ArrayList<Allocation>> priorityToAllocationListMap = initializePriorityToAllocationListMap(sortedPriorityList);

    Project project = initializeProject(unsolvedJobScheduling);

    Job sourceJob = getSourceJob(unsolvedJobScheduling, project, priorityToJobListMap, sortedPriorityList);
    project.getJobList().add(sourceJob);
    unsolvedJobScheduling.getJobList().add(sourceJob);

    Allocation sourceAllocation = getSourceAllocation(project, priorityToAllocationListMap, sortedPriorityList, sourceJob);
    unsolvedJobScheduling.getAllocationList().add(sourceAllocation);
    List<Allocation> sourceAllocationList = Lists.newArrayList(sourceAllocation);

    Job sinkJob = getSinkJob(unsolvedJobScheduling, project);
    project.getJobList().add(sinkJob);
    unsolvedJobScheduling.getJobList().add(sinkJob);
    List<Job> sinkJobList = Lists.newArrayList(sinkJob);

    Allocation sinkAllocation = getSinkAllocation(project, priorityToAllocationListMap, sortedPriorityList, sourceJob, manufOrder);
    List<Allocation> sinkAllocationList = Lists.newArrayList(sinkAllocation);

    Integer allocationIdx = 0;
    for (Integer priorityIdx = 0; priorityIdx < sortedPriorityList.size(); priorityIdx++) {
      int priority = sortedPriorityList.get(priorityIdx);
      for (OperationOrder operationOrder : priorityToOperationOrderListMap.get(priority)) {
        // Job
        Job job = getJob(unsolvedJobScheduling, project, priorityIdx, sortedPriorityList, priorityToJobListMap, sinkJobList);
        project.getJobList().add(job);
        unsolvedJobScheduling.getJobList().add(job);
        priorityToJobListMap.get(priority).add(job);

        // Execution Mode
        ExecutionMode executionMode = getExecutionMode(unsolvedJobScheduling, job, operationOrder, manufOrder, granularity);
        unsolvedJobScheduling.getExecutionModeList().add(executionMode);
        job.getExecutionModeList().add(executionMode);

        // Resource Requirement
        ResourceRequirement resourceRequirement = getResourceRequirement(unsolvedJobScheduling, executionMode, operationOrder, machineCodeToResourceMap);
        unsolvedJobScheduling.getResourceRequirementList().add(resourceRequirement);
        executionMode.getResourceRequirementList().add(resourceRequirement);

        // Allocation
        Allocation allocation = getAllocation(project, allocationIdx, job, priorityIdx, priorityToAllocationListMap, sourceAllocation, sinkAllocation, sortedPriorityList, sourceAllocationList, sinkAllocationList, allocationIdToOperationOrderMap, operationOrder);
        allocationIdx++;
        unsolvedJobScheduling.getAllocationList().add(allocation);
        priorityToAllocationListMap.get(priority).add(allocation);

        // Pinned job
        boolean isOperationOrderStarted =
            operationOrder.getStatusSelect() == OperationOrderRepository.STATUS_IN_PROGRESS
                || operationOrder.getStatusSelect() == OperationOrderRepository.STATUS_STANDBY
                || operationOrder.getStatusSelect() == OperationOrderRepository.STATUS_FINISHED;
        if ((operationOrder.getIsPinned() || isOperationOrderStarted)
            && operationOrder.getPlannedStartDateT() != null) {
          job.setPinned(true);
          job.setPinnedDate(
              (int) ChronoUnit.SECONDS.between(now, operationOrder.getPlannedStartDateT()) / granularity);
          job.setPinnedExecutionMode(executionMode);
        }
      }
    }

    unsolvedJobScheduling.getAllocationList().add(sinkAllocation);

    return project;
  }
}
