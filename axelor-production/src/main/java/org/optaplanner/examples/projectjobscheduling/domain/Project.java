/*
 * Copyright 2010 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.examples.projectjobscheduling.domain;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import java.util.ArrayList;
import java.util.List;
import org.optaplanner.examples.projectjobscheduling.domain.resource.LocalResource;

@XStreamAlias("PjsProject")
public class Project extends AbstractPersistable {

  private int releaseDate;
  private int criticalPathDuration;
  private int priority;

  private List<LocalResource> localResourceList;
  private List<Job> jobList;
  private Job sourceJob;
  private Job sinkJob;

  public Project(int priority) {
    this(priority, 0);
  }

  public Project(int priority, int releaseDate) {
    this.priority = priority;
    this.releaseDate = releaseDate;

    this.jobList = new ArrayList<Job>();
    this.localResourceList = new ArrayList<LocalResource>();
  }

  public int getPriority() {
    return this.priority;
  }

  public int getReleaseDate() {
    return this.releaseDate;
  }

  public int getCriticalPathDuration() {
    return this.criticalPathDuration;
  }

  public void setCriticalPathDuration(int criticalPathDuration) {
    this.criticalPathDuration = criticalPathDuration;
  }

  public void addJob(Job job) {
    if (job.getJobType() == JobType.SOURCE) this.sourceJob = job;
    if (job.getJobType() == JobType.SINK) this.sinkJob = job;
    this.jobList.add(job);
  }

  public List<Job> getJobList() {
    return this.jobList;
  }

  public Job getSourceJob() {
    return this.sourceJob;
  }

  public Job getSinkJob() {
    return this.sinkJob;
  }

  // ************************************************************************
  // Complex methods
  // ************************************************************************

  public int getCriticalPathEndDate() {
    return this.releaseDate + this.criticalPathDuration;
  }

  public String getLabel() {
    return "Project " + this.id;
  }
}
