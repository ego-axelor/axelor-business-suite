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
package com.axelor.apps.production.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.service.ProductVariantService;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.web.ProjectJobSchedulingHelloWorld;
import com.axelor.apps.production.db.BillOfMaterial;
import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.ProdProcess;
import com.axelor.apps.production.db.ProdProcessLine;
import com.axelor.apps.production.db.ProdProduct;
import com.axelor.apps.production.db.ProdResidualProduct;
import com.axelor.apps.production.db.repo.ManufOrderRepository;
import com.axelor.apps.production.exceptions.IExceptionMessage;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.apps.production.service.config.StockConfigProductionService;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.stock.service.StockMoveLineService;
import com.axelor.apps.stock.service.StockMoveService;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import com.thoughtworks.xstream.XStream;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.examples.common.app.CommonApp;
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
import org.optaplanner.persistence.xstream.impl.domain.solution.XStreamSolutionFileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManufOrderServiceImpl implements ManufOrderService {

  private final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected SequenceService sequenceService;
  protected OperationOrderService operationOrderService;
  protected ManufOrderWorkflowService manufOrderWorkflowService;
  protected ProductVariantService productVariantService;
  protected AppProductionService appProductionService;
  protected ManufOrderRepository manufOrderRepo;

  @Inject
  public ManufOrderServiceImpl(
      SequenceService sequenceService,
      OperationOrderService operationOrderService,
      ManufOrderWorkflowService manufOrderWorkflowService,
      ProductVariantService productVariantService,
      AppProductionService appProductionService,
      ManufOrderRepository manufOrderRepo) {
    this.sequenceService = sequenceService;
    this.operationOrderService = operationOrderService;
    this.manufOrderWorkflowService = manufOrderWorkflowService;
    this.productVariantService = productVariantService;
    this.appProductionService = appProductionService;
    this.manufOrderRepo = manufOrderRepo;
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public ManufOrder generateManufOrder(
      Product product,
      BigDecimal qtyRequested,
      int priority,
      boolean isToInvoice,
      BillOfMaterial billOfMaterial,
      LocalDateTime plannedStartDateT)
      throws AxelorException {

    if (billOfMaterial == null) {
      billOfMaterial = this.getBillOfMaterial(product);
    }

    Company company = billOfMaterial.getCompany();

    BigDecimal qty = qtyRequested.divide(billOfMaterial.getQty(), 2, RoundingMode.HALF_EVEN);

    ManufOrder manufOrder =
        this.createManufOrder(
            product, qty, priority, IS_TO_INVOICE, company, billOfMaterial, plannedStartDateT);

    manufOrder = manufOrderWorkflowService.plan(manufOrder);

    return manufOrderRepo.save(manufOrder);
  }

  @Override
  public void createToConsumeProdProductList(ManufOrder manufOrder) {

    BigDecimal manufOrderQty = manufOrder.getQty();

    BillOfMaterial billOfMaterial = manufOrder.getBillOfMaterial();

    if (billOfMaterial.getBillOfMaterialSet() != null) {

      for (BillOfMaterial billOfMaterialLine : billOfMaterial.getBillOfMaterialSet()) {

        if (!billOfMaterialLine.getHasNoManageStock()) {

          Product product =
              productVariantService.getProductVariant(
                  manufOrder.getProduct(), billOfMaterialLine.getProduct());

          BigDecimal qty =
              billOfMaterialLine
                  .getQty()
                  .multiply(manufOrderQty)
                  .setScale(2, RoundingMode.HALF_EVEN);

          manufOrder.addToConsumeProdProductListItem(
              new ProdProduct(product, qty, billOfMaterialLine.getUnit()));
        }
      }
    }
  }

  @Override
  public void createToProduceProdProductList(ManufOrder manufOrder) {

    BigDecimal manufOrderQty = manufOrder.getQty();

    BillOfMaterial billOfMaterial = manufOrder.getBillOfMaterial();

    BigDecimal qty =
        billOfMaterial.getQty().multiply(manufOrderQty).setScale(2, RoundingMode.HALF_EVEN);

    // add the produced product
    manufOrder.addToProduceProdProductListItem(
        new ProdProduct(manufOrder.getProduct(), qty, billOfMaterial.getUnit()));

    // Add the residual products
    if (appProductionService.getAppProduction().getManageResidualProductOnBom()
        && billOfMaterial.getProdResidualProductList() != null) {

      for (ProdResidualProduct prodResidualProduct : billOfMaterial.getProdResidualProductList()) {

        Product product =
            productVariantService.getProductVariant(
                manufOrder.getProduct(), prodResidualProduct.getProduct());

        qty =
            prodResidualProduct
                .getQty()
                .multiply(manufOrderQty)
                .setScale(
                    appProductionService.getNbDecimalDigitForBomQty(), RoundingMode.HALF_EVEN);

        manufOrder.addToProduceProdProductListItem(
            new ProdProduct(product, qty, prodResidualProduct.getUnit()));
      }
    }
  }

  @Override
  public ManufOrder createManufOrder(
      Product product,
      BigDecimal qty,
      int priority,
      boolean isToInvoice,
      Company company,
      BillOfMaterial billOfMaterial,
      LocalDateTime plannedStartDateT)
      throws AxelorException {

    logger.debug("Création d'un OF {}", priority);

    ProdProcess prodProcess = billOfMaterial.getProdProcess();

    ManufOrder manufOrder =
        new ManufOrder(
            qty,
            company,
            null,
            priority,
            this.isManagedConsumedProduct(billOfMaterial),
            billOfMaterial,
            product,
            prodProcess,
            plannedStartDateT,
            ManufOrderRepository.STATUS_DRAFT);

    if (prodProcess != null && prodProcess.getProdProcessLineList() != null) {
      for (ProdProcessLine prodProcessLine :
          this._sortProdProcessLineByPriority(prodProcess.getProdProcessLineList())) {

        manufOrder.addOperationOrderListItem(
            operationOrderService.createOperationOrder(manufOrder, prodProcessLine));
      }
    }

    if (!manufOrder.getIsConsProOnOperation()) {
      this.createToConsumeProdProductList(manufOrder);
    }

    this.createToProduceProdProductList(manufOrder);

    return manufOrder;
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void preFillOperations(ManufOrder manufOrder) throws AxelorException {

    BillOfMaterial billOfMaterial = manufOrder.getBillOfMaterial();

    if (manufOrder.getProdProcess() == null) {
      manufOrder.setProdProcess(billOfMaterial.getProdProcess());
    }
    ProdProcess prodProcess = manufOrder.getProdProcess();

    if (manufOrder.getPlannedStartDateT() == null) {
      manufOrder.setPlannedStartDateT(appProductionService.getTodayDateTime().toLocalDateTime());
    }

    if (prodProcess != null && prodProcess.getProdProcessLineList() != null) {

      for (ProdProcessLine prodProcessLine :
          this._sortProdProcessLineByPriority(prodProcess.getProdProcessLineList())) {
        manufOrder.addOperationOrderListItem(
            operationOrderService.createOperationOrder(manufOrder, prodProcessLine));
      }
    }

    manufOrderRepo.save(manufOrder);

    manufOrder.setPlannedEndDateT(manufOrderWorkflowService.computePlannedEndDateT(manufOrder));

    manufOrderRepo.save(manufOrder);
  }

  /**
   * Trier une liste de ligne de règle de template
   *
   * @param templateRuleLine
   */
  public List<ProdProcessLine> _sortProdProcessLineByPriority(
      List<ProdProcessLine> prodProcessLineList) {

    Collections.sort(
        prodProcessLineList,
        new Comparator<ProdProcessLine>() {

          @Override
          public int compare(ProdProcessLine ppl1, ProdProcessLine ppl2) {
            return ppl1.getPriority().compareTo(ppl2.getPriority());
          }
        });

    return prodProcessLineList;
  }

  @Override
  public String getManufOrderSeq() throws AxelorException {

    String seq = sequenceService.getSequenceNumber(SequenceRepository.MANUF_ORDER);

    if (seq == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.MANUF_ORDER_SEQ));
    }

    return seq;
  }

  @Override
  public boolean isManagedConsumedProduct(BillOfMaterial billOfMaterial) {

    if (billOfMaterial != null
        && billOfMaterial.getProdProcess() != null
        && billOfMaterial.getProdProcess().getProdProcessLineList() != null) {
      for (ProdProcessLine prodProcessLine :
          billOfMaterial.getProdProcess().getProdProcessLineList()) {

        if ((prodProcessLine.getToConsumeProdProductList() != null
            && !prodProcessLine.getToConsumeProdProductList().isEmpty())) {

          return true;
        }
      }
    }

    return false;
  }

  public BillOfMaterial getBillOfMaterial(Product product) throws AxelorException {

    BillOfMaterial billOfMaterial = product.getDefaultBillOfMaterial();

    if (billOfMaterial == null && product.getParentProduct() != null) {
      billOfMaterial = product.getParentProduct().getDefaultBillOfMaterial();
    }

    if (billOfMaterial == null) {
      throw new AxelorException(
          product,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.PRODUCTION_ORDER_SALES_ORDER_NO_BOM),
          product.getName(),
          product.getCode());
    }

    return billOfMaterial;
  }

  @Override
  public BigDecimal getProducedQuantity(ManufOrder manufOrder) {
    for (StockMoveLine stockMoveLine : manufOrder.getProducedStockMoveLineList()) {
      if (stockMoveLine.getProduct().equals(manufOrder.getProduct())) {
        return stockMoveLine.getRealQty();
      }
    }
    return BigDecimal.ZERO;
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public StockMove generateWasteStockMove(ManufOrder manufOrder) throws AxelorException {
    StockMove wasteStockMove = null;
    Company company = manufOrder.getCompany();

    if (manufOrder.getWasteProdProductList() == null
        || company == null
        || manufOrder.getWasteProdProductList().isEmpty()) {
      return wasteStockMove;
    }

    StockConfigProductionService stockConfigService = Beans.get(StockConfigProductionService.class);
    StockMoveService stockMoveService = Beans.get(StockMoveService.class);
    StockMoveLineService stockMoveLineService = Beans.get(StockMoveLineService.class);
    AppBaseService appBaseService = Beans.get(AppBaseService.class);

    StockConfig stockConfig = stockConfigService.getStockConfig(company);
    StockLocation virtualStockLocation =
        stockConfigService.getProductionVirtualStockLocation(stockConfig);
    StockLocation wasteStockLocation = stockConfigService.getWasteStockLocation(stockConfig);

    wasteStockMove =
        stockMoveService.createStockMove(
            virtualStockLocation.getAddress(),
            wasteStockLocation.getAddress(),
            company,
            virtualStockLocation,
            wasteStockLocation,
            null,
            appBaseService.getTodayDate(),
            manufOrder.getWasteProdDescription(),
            StockMoveRepository.TYPE_INTERNAL);

    for (ProdProduct prodProduct : manufOrder.getWasteProdProductList()) {
      stockMoveLineService.createStockMoveLine(
          prodProduct.getProduct(),
          prodProduct.getProduct().getName(),
          prodProduct.getProduct().getDescription(),
          prodProduct.getQty(),
          prodProduct.getProduct().getCostPrice(),
          prodProduct.getUnit(),
          wasteStockMove,
          StockMoveLineService.TYPE_WASTE_PRODUCTIONS,
          false,
          BigDecimal.ZERO);
    }

    stockMoveService.validate(wasteStockMove);

    manufOrder.setWasteStockMove(wasteStockMove);
    return wasteStockMove;
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void updatePlannedQty(ManufOrder manufOrder) {
    manufOrder.clearToConsumeProdProductList();
    manufOrder.clearToProduceProdProductList();
    this.createToConsumeProdProductList(manufOrder);
    this.createToProduceProdProductList(manufOrder);

    manufOrderRepo.save(manufOrder);
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void updateRealQty(ManufOrder manufOrder, BigDecimal qtyToUpdate) throws AxelorException {
    ManufOrderStockMoveService manufOrderStockMoveService =
        Beans.get(ManufOrderStockMoveService.class);
    if (!manufOrder.getIsConsProOnOperation()) {
      manufOrderStockMoveService.createNewConsumedStockMoveLineList(manufOrder, qtyToUpdate);
      updateDiffProdProductList(manufOrder);
    } else {
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        Beans.get(OperationOrderStockMoveService.class)
            .createNewConsumedStockMoveLineList(operationOrder, qtyToUpdate);
        Beans.get(OperationOrderService.class).updateDiffProdProductList(operationOrder);
      }
    }

    manufOrderStockMoveService.createNewProducedStockMoveLineList(manufOrder, qtyToUpdate);
  }

  @Override
  public ManufOrder updateDiffProdProductList(ManufOrder manufOrder) throws AxelorException {
    List<ProdProduct> toConsumeList = manufOrder.getToConsumeProdProductList();
    List<StockMoveLine> consumedList = manufOrder.getConsumedStockMoveLineList();
    if (toConsumeList == null || consumedList == null) {
      return manufOrder;
    }
    List<ProdProduct> diffConsumeList =
        createDiffProdProductList(manufOrder, toConsumeList, consumedList);

    manufOrder.clearDiffConsumeProdProductList();
    diffConsumeList.forEach(manufOrder::addDiffConsumeProdProductListItem);
    return manufOrder;
  }

  @Override
  public List<ProdProduct> createDiffProdProductList(
      ManufOrder manufOrder,
      List<ProdProduct> prodProductList,
      List<StockMoveLine> stockMoveLineList)
      throws AxelorException {
    List<ProdProduct> diffConsumeList =
        createDiffProdProductList(prodProductList, stockMoveLineList);
    diffConsumeList.forEach(prodProduct -> prodProduct.setDiffConsumeManufOrder(manufOrder));
    return diffConsumeList;
  }

  @Override
  public List<ProdProduct> createDiffProdProductList(
      List<ProdProduct> prodProductList, List<StockMoveLine> stockMoveLineList)
      throws AxelorException {
    List<ProdProduct> diffConsumeList = new ArrayList<>();
    for (ProdProduct prodProduct : prodProductList) {
      Product product = prodProduct.getProduct();
      Unit newUnit = prodProduct.getUnit();
      List<StockMoveLine> stockMoveLineProductList =
          stockMoveLineList
              .stream()
              .filter(stockMoveLine1 -> stockMoveLine1.getProduct() != null)
              .filter(stockMoveLine1 -> stockMoveLine1.getProduct().equals(product))
              .collect(Collectors.toList());
      if (stockMoveLineProductList.isEmpty()) {
        continue;
      }
      BigDecimal diffQty = computeDiffQty(prodProduct, stockMoveLineProductList, product);
      if (diffQty.compareTo(BigDecimal.ZERO) != 0) {
        ProdProduct diffProdProduct = new ProdProduct();
        diffProdProduct.setQty(diffQty);
        diffProdProduct.setProduct(product);
        diffProdProduct.setUnit(newUnit);
        diffConsumeList.add(diffProdProduct);
      }
    }
    // There are stock move lines with products that are not available in
    // prod product list. It needs to appear in the prod product list
    List<StockMoveLine> stockMoveLineMissingProductList =
        stockMoveLineList
            .stream()
            .filter(stockMoveLine1 -> stockMoveLine1.getProduct() != null)
            .filter(
                stockMoveLine1 ->
                    !prodProductList
                        .stream()
                        .map(ProdProduct::getProduct)
                        .collect(Collectors.toList())
                        .contains(stockMoveLine1.getProduct()))
            .collect(Collectors.toList());
    for (StockMoveLine stockMoveLine : stockMoveLineMissingProductList) {
      if (stockMoveLine.getQty().compareTo(BigDecimal.ZERO) != 0) {
        ProdProduct diffProdProduct = new ProdProduct();
        diffProdProduct.setQty(stockMoveLine.getQty());
        diffProdProduct.setProduct(stockMoveLine.getProduct());
        diffProdProduct.setUnit(stockMoveLine.getUnit());
        diffConsumeList.add(diffProdProduct);
      }
    }
    return diffConsumeList;
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void updateConsumedStockMoveFromManufOrder(ManufOrder manufOrder) throws AxelorException {
    this.updateDiffProdProductList(manufOrder);
    List<StockMoveLine> consumedStockMoveLineList = manufOrder.getConsumedStockMoveLineList();
    if (consumedStockMoveLineList == null) {
      return;
    }
    Optional<StockMove> stockMoveOpt =
        manufOrder
            .getInStockMoveList()
            .stream()
            .filter(stockMove -> stockMove.getStatusSelect() == StockMoveRepository.STATUS_PLANNED)
            .findFirst();
    if (!stockMoveOpt.isPresent()) {
      return;
    }
    StockMove stockMove = stockMoveOpt.get();

    updateStockMoveFromManufOrder(consumedStockMoveLineList, stockMove);
  }

  @Override
  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void updateProducedStockMoveFromManufOrder(ManufOrder manufOrder) {
    List<StockMoveLine> producedStockMoveLineList = manufOrder.getProducedStockMoveLineList();
    Optional<StockMove> stockMoveOpt =
        manufOrder
            .getOutStockMoveList()
            .stream()
            .filter(stockMove -> stockMove.getStatusSelect() == StockMoveRepository.STATUS_PLANNED)
            .findFirst();
    if (!stockMoveOpt.isPresent()) {
      return;
    }
    StockMove stockMove = stockMoveOpt.get();

    updateStockMoveFromManufOrder(producedStockMoveLineList, stockMove);
  }

  @Override
  public void updateStockMoveFromManufOrder(
      List<StockMoveLine> stockMoveLineList, StockMove stockMove) {
    if (stockMoveLineList == null) {
      return;
    }

    // add missing lines in stock move
    stockMoveLineList
        .stream()
        .filter(stockMoveLine -> stockMoveLine.getStockMove() == null)
        .forEach(stockMove::addStockMoveLineListItem);

    // remove lines in stock move removed in manuf order
    if (stockMove.getStockMoveLineList() != null) {
      stockMove
          .getStockMoveLineList()
          .removeIf(stockMoveLine -> !stockMoveLineList.contains(stockMoveLine));
    }
  }

  /**
   * Compute the difference in qty between a prodProduct and the qty in a list of stock move lines.
   *
   * @param prodProduct
   * @param stockMoveLineList
   * @param product
   * @return
   * @throws AxelorException
   */
  protected BigDecimal computeDiffQty(
      ProdProduct prodProduct, List<StockMoveLine> stockMoveLineList, Product product)
      throws AxelorException {
    BigDecimal consumedQty = BigDecimal.ZERO;
    for (StockMoveLine stockMoveLine : stockMoveLineList) {
      if (stockMoveLine.getUnit() != null && prodProduct.getUnit() != null) {
        consumedQty =
            consumedQty.add(
                Beans.get(UnitConversionService.class)
                    .convertWithProduct(
                        stockMoveLine.getUnit(),
                        prodProduct.getUnit(),
                        stockMoveLine.getQty(),
                        product));
      } else {
        consumedQty = consumedQty.add(stockMoveLine.getQty());
      }
    }
    return consumedQty.subtract(prodProduct.getQty());
  }

  @Transactional(rollbackOn = {AxelorException.class, Exception.class})
  public void optaPlan(ManufOrder manufOrder) {
    System.out.println("Will OptaPLAN !");
    
    // Build the Solver
    SolverFactory<Schedule> solverFactory =
        SolverFactory.createFromXmlResource(
            "projectjobscheduling/solver/projectJobSchedulingSolverConfig.xml");
    Solver<Schedule> solver = solverFactory.buildSolver();

    // Load a problem
    /*
    File outputDir =
        new File(
            CommonApp.determineDataDir(ProjectJobSchedulingHelloWorld.DATA_DIR_NAME),
            "unsolved/A-1.xml");
    XStreamSolutionFileIO<Schedule> solutionFileIO = new XStreamSolutionFileIO<>(Schedule.class);
    Schedule unsolvedJobScheduling = solutionFileIO.read(outputDir);
    */

    // Custom Unsolved Job Scheduling 
    Schedule unsolvedJobScheduling = new Schedule();
    unsolvedJobScheduling.setJobList(new ArrayList<Job>());
    unsolvedJobScheduling.setProjectList(new ArrayList<Project>());
    unsolvedJobScheduling.setResourceList(new ArrayList<Resource>());
    unsolvedJobScheduling.setResourceRequirementList(new ArrayList<ResourceRequirement>());
    unsolvedJobScheduling.setExecutionModeList(new ArrayList<ExecutionMode>());
    unsolvedJobScheduling.setAllocationList(new ArrayList<Allocation>());

    List<OperationOrder> operationOrders = manufOrder.getOperationOrderList();
    BigDecimal qty = manufOrder.getQty();
    
    Map<Integer, List<OperationOrder>> priorityToOperationOrderMap = new HashMap<>();
    for(OperationOrder curOperationOrder : operationOrders) {
        int priority = curOperationOrder.getPriority();
        if(!priorityToOperationOrderMap.containsKey(priority)) {
        	priorityToOperationOrderMap.put(priority, new ArrayList<OperationOrder>());
        }
        priorityToOperationOrderMap.get(priority).add(curOperationOrder);
    }
    
    // Create Resources
    Set<String> requiredMachines = new HashSet<>();
    for(OperationOrder operationOrder : operationOrders) {
    	requiredMachines.add(operationOrder.getMachineWorkCenter().getCode());
    }
    Map<String, Resource> machineCodeToResourceMap = new HashMap<>();
    for(String machineCode : requiredMachines) {
    	Resource curResource = new GlobalResource();
    	curResource.setCapacity(1);
    	long resourceId = unsolvedJobScheduling.getResourceList().size() > 0 ? unsolvedJobScheduling.getResourceList().get(unsolvedJobScheduling.getResourceList().size() - 1).getId() + 1 : 0;
      	curResource.setId(resourceId);
    	machineCodeToResourceMap.put(machineCode, curResource);
    	unsolvedJobScheduling.getResourceList().add(curResource);
    }
    
    Map<Long, String> idToMachineCodeMap = new HashMap<>();
    
    // Create projects
    for(int i=0;i<qty.intValue();i++) {
    	createProject(unsolvedJobScheduling, priorityToOperationOrderMap, operationOrders, machineCodeToResourceMap, idToMachineCodeMap);
    }
    
    //unsolvedJobScheduling.setId(1L);
    
	/*
    System.out.println("Allocation list : " + unsolvedJobScheduling.getAllocationList().size());
    System.out.println("Job list : " + unsolvedJobScheduling.getJobList().size());
    System.out.println("Project list : " + unsolvedJobScheduling.getProjectList().size());
    System.out.println("Resource list : " + unsolvedJobScheduling.getResourceList().size());
    System.out.println("Resource Requirement list : " + unsolvedJobScheduling.getResourceRequirementList().size());
    System.out.println("Execution Mode list : " + unsolvedJobScheduling.getExecutionModeList().size());
    */

    // Solve the problem
    Schedule solvedJobScheduling = solver.solve(unsolvedJobScheduling);

    for(Allocation allo : solvedJobScheduling.getAllocationList()) {
    	System.out.println(StringUtils.rightPad(allo + " Machine : " + idToMachineCodeMap.get(allo.getId()), 50) + StringUtils.rightPad(" Delay : " + allo.getDelay(), 15) + " StartDate : " + allo.getStartDate());
    }
    
    for(Allocation allo : solvedJobScheduling.getAllocationList()) {
    	String spaces = "";
    	for(int i=0;i<allo.getStartDate();i++)
    		spaces += " ";
    	String dashes = "";
    	for(int i=allo.getStartDate();i<allo.getEndDate();i++)
    		dashes += "#";
    	System.out.println(StringUtils.rightPad(allo + " Machine : " + idToMachineCodeMap.get(allo.getId()), 50) + spaces + dashes);
    }
    System.out.println("Critical path duration : " + solvedJobScheduling.getProjectList().get(0).getCriticalPathDuration());
  }
  
  private int getCriticalPathDuration(List<OperationOrder> operationOrders) {
    int criticalPathDuration = 0;
    Map<Integer, Long> priorityToDurationMap = new HashMap<>();
    for(OperationOrder curOperationOrder : operationOrders) {
        int priority = curOperationOrder.getPriority();
        long duration = curOperationOrder.getProdHumanResourceList().get(0).getDuration();
        if(!priorityToDurationMap.containsKey(priority) || priorityToDurationMap.get(priority) < duration) {
            priorityToDurationMap.put(priority, duration);
        }
    }
    for(Integer key : priorityToDurationMap.keySet()) {
        criticalPathDuration += priorityToDurationMap.get(key);
    }
    System.out.println("The critical path duration is : " + criticalPathDuration);
    return criticalPathDuration / 60;
  }
  
  private int getCriticalPathDuration(Project project) {
	  Job sourceJob = null;
	  for(Job curJob : project.getJobList()) {
		  if(curJob.getJobType() == JobType.SOURCE) {
			  sourceJob = curJob;
			  break;
		  }
	  }
	  if(sourceJob != null) {
		  return getCriticalPathDuration(sourceJob);
	  }
	  return 0;
  }
  
  private int getCriticalPathDuration(Job job) {
	  if(job.getJobType() == JobType.SINK) {
		  return 0;
	  }
	  else {
		  int maximumCriticalPathDuration = 0;
		  for(Job successorJob : job.getSuccessorJobList()) {
			  int curCriticalPathDuration = getCriticalPathDuration(successorJob);
			  if(curCriticalPathDuration > maximumCriticalPathDuration) {
				  maximumCriticalPathDuration = curCriticalPathDuration;
			  }
		  }
		  return maximumCriticalPathDuration + maximumExecutionModeDuration(job);
	  }
  }
  
  private int maximumExecutionModeDuration(Job job) {
	  int maximumExecutionModeDuration = 0;
	  if(job.getExecutionModeList() != null) {
		  for(ExecutionMode executionMode : job.getExecutionModeList()) {
			  if(maximumExecutionModeDuration < executionMode.getDuration()) {
				  maximumExecutionModeDuration = executionMode.getDuration();
			  }
		  }
		  return maximumExecutionModeDuration;
	  }
	  return 0;
  }
  
  private void createProject(Schedule unsolvedJobScheduling, Map<Integer, List<OperationOrder>> priorityToOperationOrderMap, List<OperationOrder> operationOrders, Map<String, Resource> machineCodeToResourceMap, Map<Long, String> idToMachineCodeMap) {
	long projectId = unsolvedJobScheduling.getProjectList().size() > 0 ? unsolvedJobScheduling.getProjectList().get(unsolvedJobScheduling.getProjectList().size() - 1).getId() + 1 : 1;

  	SortedSet<Integer> keySet = new TreeSet<Integer>(priorityToOperationOrderMap.keySet());
  	List<Integer> keyList = new ArrayList<Integer>(keySet);

  	Project curProject = new Project();
  	List<Job> curJobList = new ArrayList<Job>();
  	Map<Integer, ArrayList<Job>> priorityToJobMap = new HashMap<>();
  	for(Integer priority : keyList) {
  		priorityToJobMap.put(priority, new ArrayList<Job>());
  	}
  	Map<Integer, ArrayList<Allocation>> priorityToAllocationMap = new HashMap<>();
  	for(Integer priority : keyList) {
      	priorityToAllocationMap.put(priority, new ArrayList<Allocation>());
  	}
  	List<Allocation> curAllocationList = new ArrayList<>();

  	Allocation sourceAllocation = new Allocation();
  	//sourceAllocation.setDelay(0);
  	sourceAllocation.setPredecessorsDoneDate(0);
  	sourceAllocation.setId((long) (projectId*100));
  	//sourceAllocation.setPredecessorAllocationList(new ArrayList<Allocation>());
  	sourceAllocation.setSuccessorAllocationList(priorityToAllocationMap.get(keyList.get(0)));
  	sourceAllocation.setPredecessorsDoneDate(0);
  	curAllocationList.add(sourceAllocation);
  	unsolvedJobScheduling.getAllocationList().add(sourceAllocation);
  	List<Allocation> sourceAllocationList = new ArrayList<>();
  	sourceAllocationList.add(sourceAllocation);
  	Job sourceJob = new Job();
  	sourceJob.setProject(curProject);
  	long sourceJobId = unsolvedJobScheduling.getJobList().size() > 0 ? unsolvedJobScheduling.getJobList().get(unsolvedJobScheduling.getJobList().size() - 1).getId() + 1 : 0;
  	sourceJob.setId(sourceJobId);
  	sourceJob.setJobType(JobType.SOURCE);
  	sourceJob.setSuccessorJobList(priorityToJobMap.get(keyList.get(0)));
  	sourceAllocation.setJob(sourceJob);
  	curJobList.add(sourceJob);
  	unsolvedJobScheduling.getJobList().add(sourceJob);
  	
  	Allocation sinkAllocation = new Allocation();
  	//sinkAllocation.setDelay(0);
  	sinkAllocation.setPredecessorsDoneDate(0);
  	sinkAllocation.setId((long) (projectId*100 + operationOrders.size() + 1));
  	sinkAllocation.setPredecessorAllocationList(priorityToAllocationMap.get(keyList.get(keyList.size()-1)));
  	sinkAllocation.setSuccessorAllocationList(new ArrayList<Allocation>());
  	sinkAllocation.setPredecessorsDoneDate(0);
  	curAllocationList.add(sinkAllocation);
  	//allocationList.add(sinkAllocation);
  	List<Allocation> sinkAllocationList = new ArrayList<>();
  	sinkAllocationList.add(sinkAllocation);
  	Job sinkJob = new Job();
  	sinkJob.setProject(curProject);
  	long sinkJobId = unsolvedJobScheduling.getJobList().size() > 0 ? unsolvedJobScheduling.getJobList().get(unsolvedJobScheduling.getJobList().size() - 1).getId() + 1 : 0;
  	sinkJob.setId(sinkJobId);
  	sinkJob.setJobType(JobType.SINK);
  	//sinkJob.setSuccessorJobList(priorityToJobMap.get(keyList.get(keyList.size()-1)));
  	sinkAllocation.setJob(sinkJob);
  	curJobList.add(sinkJob);
  	unsolvedJobScheduling.getJobList().add(sinkJob);
  	List<Job> sinkJobList = new ArrayList<>();
  	sinkJobList.add(sinkJob);
  	
  	int c=0;
  	for(int j=0;j<keyList.size();j++) {
  		int priority = keyList.get(j);
  		for(OperationOrder curOperationOrder : priorityToOperationOrderMap.get(priority)) {
  			Job curJob = new Job();
  			
  			ExecutionMode curExecutionMode = new ExecutionMode();
  			long duration = curOperationOrder.getProdHumanResourceList().get(0).getDuration();
  			curExecutionMode.setDuration((int) duration/60);
  			curExecutionMode.setJob(curJob);
  			long executionModeId = unsolvedJobScheduling.getExecutionModeList().size() > 0 ? unsolvedJobScheduling.getExecutionModeList().get(unsolvedJobScheduling.getExecutionModeList().size() - 1).getId() + 1 : 0;
  			curExecutionMode.setId(executionModeId);
  			List<ExecutionMode> curExecutionModeList = new ArrayList<ExecutionMode>();
  			curExecutionModeList.add(curExecutionMode);
  			unsolvedJobScheduling.getExecutionModeList().add(curExecutionMode);
  			curJob.setExecutionModeList(curExecutionModeList);
  			
  			List<ResourceRequirement> curResourceRequirementList = new ArrayList<>();
  			ResourceRequirement curResourceRequirement = new ResourceRequirement();
  			curResourceRequirement.setExecutionMode(curExecutionMode);
  			Resource curResource = machineCodeToResourceMap.get(curOperationOrder.getMachineWorkCenter().getCode());
  			curResourceRequirement.setResource(curResource);
  			long resourceRequirementId = unsolvedJobScheduling.getResourceRequirementList().size() > 0 ? unsolvedJobScheduling.getResourceRequirementList().get(unsolvedJobScheduling.getResourceRequirementList().size() - 1).getId() + 1 : 0;
  			curResourceRequirement.setId(resourceRequirementId);
  			curResourceRequirement.setRequirement(1);
  			curResourceRequirementList.add(curResourceRequirement);
  			curExecutionMode.setResourceRequirementList(curResourceRequirementList);
  			unsolvedJobScheduling.getResourceRequirementList().add(curResourceRequirement);
  			

  			curJob.setJobType(JobType.STANDARD);
  			curJob.setProject(curProject);
  			if(j < keyList.size() - 1) {
  				curJob.setSuccessorJobList(priorityToJobMap.get(keyList.get(j + 1)));
  			}
  			else {
  				curJob.setSuccessorJobList(sinkJobList);
  			}
  			Allocation curAllocation = new Allocation();
  			curAllocation.setJob(curJob);
  			List<Allocation> predecessorAllocationList = j > 0 ? priorityToAllocationMap.get(keyList.get(j - 1)) : sourceAllocationList;
  			curAllocation.setPredecessorAllocationList(predecessorAllocationList);
  			List<Allocation> successorAllocationList = j < keyList.size() - 1 ? priorityToAllocationMap.get(keyList.get(j + 1)) : sinkAllocationList;
  			curAllocation.setSuccessorAllocationList(successorAllocationList);
  			Long id = (long) (projectId*100+(c+1));
  			curAllocation.setId(id);
  			c++;
  			idToMachineCodeMap.put(id, curOperationOrder.getMachineWorkCenter().getName());
  			//if(j == 0 || j == keyList.size() - 1)
  			//	curAllocation.setDelay(0);
  	    	curAllocation.setPredecessorsDoneDate(0);
  			curAllocation.setSourceAllocation(sourceAllocation);
  			curAllocation.setSinkAllocation(sinkAllocation);
  	    	curAllocationList.add(curAllocation);
  	    	unsolvedJobScheduling.getAllocationList().add(curAllocation);
  			curAllocation.setPredecessorsDoneDate(0);
  			priorityToAllocationMap.get(priority).add(curAllocation);
  			
  			long jobId = unsolvedJobScheduling.getJobList().size() > 0 ? unsolvedJobScheduling.getJobList().get(unsolvedJobScheduling.getJobList().size() - 1).getId() + 1 : 0;
  			curJob.setId(jobId);
  			
  			priorityToJobMap.get(priority).add(curJob);
  			
  			curJobList.add(curJob);
  			unsolvedJobScheduling.getJobList().add(curJob);
  		}
  	}
  	
  	curProject.setLocalResourceList(new ArrayList<LocalResource>());
  	curProject.setReleaseDate(0);
  	//curProject.setCriticalPathDuration(getCriticalPathDuration(operationOrders));
  	curProject.setJobList(curJobList);
  	curProject.setCriticalPathDuration(getCriticalPathDuration(curProject));
		
  	curProject.setId(projectId);

  	unsolvedJobScheduling.getProjectList().add(curProject);
		
  	unsolvedJobScheduling.getAllocationList().add(sinkAllocation);
  }
}
