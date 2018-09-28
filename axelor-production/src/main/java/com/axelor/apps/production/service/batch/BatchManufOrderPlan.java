/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.production.service.batch;

import com.axelor.apps.base.db.repo.ICalendarRepository;
import com.axelor.apps.base.ical.ICalendarService;
import com.axelor.apps.base.service.administration.AbstractBatch;
import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.repo.ManufOrderRepository;
import com.axelor.apps.production.service.ManufOrderWorkflowService;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;

public class BatchManufOrderPlan extends AbstractBatch {

  @Inject ICalendarService iCalendarService;

  @Inject ICalendarRepository repo;

  @Override
  protected void process() {
    LocalDateTime now = Beans.get(AppProductionService.class).getTodayDateTime().toLocalDateTime();
    List<ManufOrder> manufOrderList =
        Beans.get(ManufOrderRepository.class)
            .all()
            .filter("self.plannedEndDateT > :now AND self.statusSelect != 2")
            .bind("now", now)
            .fetch();

    try {
      Beans.get(ManufOrderWorkflowService.class).plan(manufOrderList, false);
      incrementDone();
    } catch (Exception e) {
      e.printStackTrace();
      incrementAnomaly();
    }
  }
}
