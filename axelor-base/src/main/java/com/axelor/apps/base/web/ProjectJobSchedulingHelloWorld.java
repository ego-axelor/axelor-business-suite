/*
 * Copyright 2012 Red Hat, Inc. and/or its affiliates.
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

package com.axelor.apps.base.web;

import java.io.File;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.examples.common.app.CommonApp;
import org.optaplanner.examples.projectjobscheduling.domain.Schedule;
import org.optaplanner.persistence.xstream.impl.domain.solution.XStreamSolutionFileIO;

import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;

public class ProjectJobSchedulingHelloWorld {

  public static final String DATA_DIR_NAME = "projectjobscheduling";

  public void test(ActionRequest request, ActionResponse response) {
	    // Build the Solver
	    SolverFactory<Schedule> solverFactory =
	        SolverFactory.createFromXmlResource(
	            "projectjobscheduling/solver/projectJobSchedulingSolverConfig.xml");
	    Solver<Schedule> solver = solverFactory.buildSolver();

	    // Load a problem
	    File outputDir =
	        new File(
	            CommonApp.determineDataDir(ProjectJobSchedulingHelloWorld.DATA_DIR_NAME),
	            "unsolved/A-1.xml");
	    XStreamSolutionFileIO<Schedule> solutionFileIO = new XStreamSolutionFileIO<>(Schedule.class);
	    Schedule unsolvedJobScheduling = solutionFileIO.read(outputDir);

	    // Solve the problem
	    Schedule solvedJobScheduling = solver.solve(unsolvedJobScheduling);

	    // Display the result
	    System.out.println("\nSolved Job scheduling:\n" + solvedJobScheduling);
  }
}
