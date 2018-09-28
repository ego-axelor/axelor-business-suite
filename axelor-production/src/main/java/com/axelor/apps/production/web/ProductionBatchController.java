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
package com.axelor.apps.production.web;

import com.axelor.apps.base.db.Batch;
import com.axelor.apps.production.db.ProductionBatch;
import com.axelor.apps.production.db.repo.ProductionBatchRepository;
import com.axelor.apps.production.service.batch.BatchManufOrderPlan;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class ProductionBatchController {

  @Inject private ProductionBatchRepository productionBatchRepo;

  public void launchProductionBatch(ActionRequest request, ActionResponse response)
      throws AxelorException {

    ProductionBatch productionBatch = request.getContext().asType(ProductionBatch.class);

    Batch batch =
        Beans.get(BatchManufOrderPlan.class).run(productionBatchRepo.find(productionBatch.getId()));

    if (batch != null) response.setFlash(batch.getComments());
    response.setReload(true);
  }
}
