package org.cloudbus.cloudsim.simulate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

public class CustomDatacenterBroker extends DatacenterBroker {
	public static final int STOPPED = 0;
	public static final int RUNNING = 1;

	private Map<Integer, Map<Integer, EstimationCloudletObserve>> cloudletEstimateObserveMap;
	
	// list cloudlet waiting for internal estimate
	private List<Cloudlet> estimationList;
	private List<ScaleObject> scaleList;
		
	private int estimationStatus = STOPPED;
	private List<PartnerInfomation> partnersList = new ArrayList<PartnerInfomation>();
	private double vmSize = 0;
	protected Map<Integer,EstimationCloudletOfPartner> estimateCloudletofParnerMap;
	
	public CustomDatacenterBroker(String name) throws Exception {
		super(name);
		setEstimationList(new ArrayList<Cloudlet>());
		setScaleList(new ArrayList<ScaleObject>());
		setCloudletEstimateObserveMap(new HashMap<Integer, Map<Integer, EstimationCloudletObserve>>());
		setPartnersList(new ArrayList<PartnerInfomation>());
		setEstimateCloudletofParnerMap(new HashMap<Integer, EstimationCloudletOfPartner>());
	}
	
	@Override
	public void processEvent(SimEvent ev) {
		switch (ev.getTag()) {
		// Resource characteristics request
			case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
				processResourceCharacteristicsRequest(ev);
				break;
			// Resource characteristics answer
			case CloudSimTags.RESOURCE_CHARACTERISTICS:
				processResourceCharacteristics(ev);
				break;
			// VM Creation answer
			case CloudSimTags.VM_CREATE_ACK:
				processVmCreate(ev);
				break;
			// A finished cloudlet returned
			case CloudSimTags.CLOUDLET_RETURN:
				processCloudletReturn(ev);
				break;
			// if the simulation finishes
			case CloudSimTags.END_OF_SIMULATION:
				shutdownEntity();
				break;
			case CloudSimTags.BROKER_ESTIMATE_NEXT_TASK:
				estimateNextTask();
				break;
				
			case CloudSimTags.BROKER_ESTIMATE_RETURN:
//				processInternalEstimateReturn(ev);
				processIncomingCloudlet(ev);
				break;
				
			/* NO USAGE: handle request send task to partner estimate form my datacenter  **/
			case CloudSimTags.PARTNER_INTERNAL_ESTIMATE_REQUEST:
				processPartnerCloudletInternalEstimateRequest(ev);
				break;
			/* handle request estimate from partner **/
			case CloudSimTags.PARTNER_ESTIMATE_REQUEST:
				handlerPartnerCloudletEstimateRequest(ev);
				break;
			//if the cloudle estimate result returned from partner
			case CloudSimTags.PARTNER_ESTIMATE_RETURN: 
				processReturnEstimateFromPartner(ev);
				break;
				
			case CloudSimTags.PARTNER_EXEC:
				processPartnerCloudletExecRequest(ev);
				break;
				
			case CloudSimTags.PARTNER_CANCEL_ESTIMATED_TASK:
				processPartnerCloudletCancelRequest(ev);
				break;
				
			case CloudSimTags.BROKER_SCALE:
				processScale();
				break;
				
			case CloudSimTags.PARTNER_SCALE:
				processPartnerScale(ev);
				break;

			// other unknown tags are processed by this method
			default:
				processOtherEvent(ev);
				break;
		}
	}

	@Override
	protected void submitCloudlets() {
		Log.printLine(this.getName() + " submit Cloudlet");
		for (Cloudlet cloudlet: getCloudletList()) {
			addCloudletToEstimationList(cloudlet);
//			Log.printLine("Cloudlet #" + cloudlet.getCloudletId() + " has been submitted!");
		}
	}
	private void addCloudletToEstimationList(Cloudlet cloudlet) {
		getEstimationList().add(cloudlet);
		Collections.sort(getEstimationList(), new CloudletComparator());
		if (estimationStatus == STOPPED) {
//			setEstimationStatus(RUNNING);
			sendNow(getId(), CloudSimTags.BROKER_ESTIMATE_NEXT_TASK);
			if (Simulate.SCALABLE) {
				sendNow(getId(), CloudSimTags.BROKER_SCALE);
			}
		}
	}
	
	private void estimateNextTask() {
		if (getEstimationList().isEmpty()) {
			setEstimationStatus(STOPPED);
		} else {
			if (getEstimationStatus() == STOPPED) {
				Cloudlet cloudlet = getEstimationList().get(0);
				
				if (cloudlet.getUserRequestTime() <= CloudSim.clock()) {
					setEstimationStatus(RUNNING);
					createCloudletObserve(cloudlet);
					
					for (Integer datacenterId: getDatacenterIdsList()) {
						CustomResCloudlet rcl = new CustomResCloudlet(cloudlet);
						sendNow(datacenterId, CloudSimTags.DATACENTER_ESTIMATE_TASK, rcl);
					}
				} else {
					double delay = cloudlet.getUserRequestTime() - CloudSim.clock();

					Log.printLine(CloudSim.clock() + ": " + getName() 
							+ ": Estimate cloudlet #" + cloudlet.getCloudletId() + " received at " + cloudlet.getUserRequestTime()
							+ " DELAY " + delay);
					send(getId(), delay, CloudSimTags.BROKER_ESTIMATE_NEXT_TASK);
				}
			}
		}
	}
	
	private void processScale() {
		if (!getScaleList().isEmpty()) {
			ScaleObject so = getScaleList().get(0);
			double delay = so.getScaleTime() - CloudSim.clock();
			if (delay > 0) {
				send(getId(), delay, CloudSimTags.BROKER_SCALE);
			} else {
				for (Integer datacenterId: getDatacenterIdsList()) {
					sendNow(datacenterId, CloudSimTags.DATACENTER_SCALE, so);
				}
				
				/* In scaling, update Broker info */
				Log.printLine(getName() + "has vmsize:" + this.getVmSize() + ": processScale addmips:" + so.getAppendMips() + "-add vmList" + so.getVmList().get(0).getMips());

				int appendMips = so.getAppendMips();
				this.submitVmList(so.getVmList());
				this.appendVmSize(appendMips);
				
				for (ScaleObject sc: getScaleList())
					Log.printLine(getName() + ": processScale list:" + sc.getScaleTime());
				
				if (Simulate.UPDATE_SCALE_PARTNER) {
					for (PartnerInfomation pi: getPartnersList()) {
						if (Simulate.USER_ALPHA_RATIO) {
							int partnerVm = pi.getPartnerVm();
							double ourVm = getVmSize();
							double newRatio = partnerVm / ourVm;

							pi.setContractRatio(partnerVm, ourVm);
							
							sendNow(pi.getPartnerId(), CloudSimTags.PARTNER_SCALE, so);
							Log.printLine(getName() + ": processScale ID:" + pi.getPartnerId() +" newRatio: " + partnerVm +"/" + ourVm);
						} else {
							// 1:1 ratio
							pi.setPartnerVm(pi.getPartnerVm() + so.getMips());
						}
					}
				}
				getScaleList().remove(0);
				sendNow(getId(), CloudSimTags.BROKER_SCALE);
			}
//			Log.printLine(CloudSim.clock() + getName() + ": processScale scaleTime: " + so.getScaleTime());
		}
	}
	
	private void processPartnerScale(SimEvent ev) {
		ScaleObject so = (ScaleObject) ev.getData();
		for (PartnerInfomation pi: getPartnersList()) {
			if (pi.getPartnerId() == ev.getSource()) {
				int partnerVm = pi.getPartnerVm();
				double ownVm = getVmSize();
				double newRatio = (partnerVm + so.getMips()) / ownVm;
	
				Log.printLine(getName() + ": processPartnerScale ID:" + pi.getPartnerId() +" newRatio: " + (partnerVm + so.getMips()) +"/" + ownVm);

				pi.setContractRatio(partnerVm + so.getMips(), ownVm);
				pi.setPartnerVm(partnerVm + so.getMips());

				break;
			}
		}
	}
	
	private void createCloudletObserve(Cloudlet cloudlet) {
		int owner = cloudlet.getUserId();
		
		Map<Integer, EstimationCloudletObserve> observeMap;
		
		if (getCloudletEstimateObserveMap().containsKey(owner)) {
			observeMap = getCloudletEstimateObserveMap().get(owner);
		} else {
			observeMap = new HashMap<Integer, EstimationCloudletObserve>(); 
			getCloudletEstimateObserveMap().put(owner, observeMap);
		}
		
		EstimationCloudletObserve observe;
		if (observeMap.containsKey(cloudlet.getCloudletId())) {
			observe = observeMap.get(cloudlet.getCloudletId());
		} else {
			observe = new EstimationCloudletObserve(new CustomResCloudlet(cloudlet), new ArrayList<>(getDatacenterIdsList()));
			observeMap.put(cloudlet.getCloudletId(), observe);
		}
	}
	
	protected void processIncomingCloudlet(SimEvent ev) {
		CustomResCloudlet re_rcl = (CustomResCloudlet) ev.getData();
		Log.printLine(getName() + ": Receive internal response from datacenter #" + ev.getSource() + ": ");// + re_rcl.getBestFinishTime());
		
		if (getCloudletEstimateObserveMap().containsKey(re_rcl.getUserId())) {
			Map<Integer, EstimationCloudletObserve> obserMap = getCloudletEstimateObserveMap().get(re_rcl.getUserId());
			EstimationCloudletObserve observe = obserMap.get(re_rcl.getCloudletId());
			int[] cancel_estimate = observe.receiveEstimateResult(ev.getSource(), re_rcl);
			
			if (cancel_estimate[0] > -1) {
				sendCancelRequest(cancel_estimate);
			}
			
			if (observe.isFinished()) {
				if (observe.getResCloudlet().getUserId() == getId()) {	// this is our cloudlet
					if (observe.isExecable()) {                         // Locally execute internal cloudlet
//						Log.printLine(getName() + ": WE CAN EXEC THIS CLOUDLET #" + observe.getResCloudlet().getCloudletId() );

						CustomResCloudlet rcl = observe.getResCloudlet();
						sendExecRequest(rcl.getBestDatacenterId(), rcl.getBestVmId(), rcl);
					} else {                                            // Out of our capacity
//						Log.printLine(CloudSim.clock() + ":" + getName() + ": WE NEED HELP FROM PARTNER #"
//								+ observe.getResCloudlet().getCloudlet().getCloudletId());
						
						CustomResCloudlet currResCloudlet = observe.getResCloudlet();
						Cloudlet currCloudlet = currResCloudlet.getCloudlet();

						double new_k=1000000000, selected_k=-2;
						PartnerInfomation best=null;
						//* INIT BY GET THE MINIMUM k */
						for (PartnerInfomation pi: getPartnersList()) {
							if (selected_k < pi.getKRatioWithCurrentTask(currCloudlet.getCloudletLength(), 0)){
								selected_k = pi.getKRatioWithCurrentTask(currCloudlet.getCloudletLength(), 0);
							    best = pi;
							}
						}
						Log.printLine(getName() + " CHUBE sent Cloudlet: init slected_k: " + selected_k + " Cloudlet: " + currCloudlet.getCloudletId());
						List<PartnerInfomation> sentPartner = new ArrayList<PartnerInfomation>();
						for (PartnerInfomation pi: getPartnersList()) {
							new_k = pi.getKRatioWithCurrentTask(currCloudlet.getCloudletLength(), 0);
							if( new_k < selected_k && new_k < 2 && new_k > -1){
								selected_k = new_k;
								best=pi;
							}
						}
						if(best != null){
						   updatePartnerInformation(best);

							sendNow(best.getPartnerId(), CloudSimTags.PARTNER_ESTIMATE_REQUEST, currResCloudlet);
							sentPartner.add(best);
							EstimationCloudletOfPartner estPartner = new EstimationCloudletOfPartner(new CustomResCloudlet(currCloudlet), 
									sentPartner ,partnersList);
							getEstimateCloudletofParnerMap().put(currCloudlet.getCloudletId(), estPartner);
						    sendExecRequest(currResCloudlet.getBestDatacenterId(), currResCloudlet.getBestVmId(), currResCloudlet);
						   Log.printLine(getName() + " CHUBE sent Cloudlet: " + currCloudlet.getCloudletId() + " to partner: " + best.getPartnerId()+ 
								   " | bestDatacenterId: "+ currResCloudlet.getBestDatacenterId() + " | bestVm: " + currResCloudlet.getBestVmId() +
								   "kratio: " + selected_k);
						   
						} else {
								sendPartnerRequest(currCloudlet);
						}
					}

					setEstimationStatus(STOPPED);
					sendNow(getId(), CloudSimTags.BROKER_ESTIMATE_NEXT_TASK);
				} else {
					// this is partner cloudlet
					sendNow(observe.getResCloudlet().getUserId(), CloudSimTags.PARTNER_ESTIMATE_RETURN, observe.getResCloudlet());
				}
				
				// TODO test
				getEstimationList().remove(re_rcl.getCloudlet());
			}
		}
	}
	
	private void processPartnerCloudletExecRequest(SimEvent ev) {
		CustomResCloudlet rcl = (CustomResCloudlet) ev.getData();
		
		updatePartnerInformationByValue(ev.getSource(),0,rcl.getCloudletLength());
		sendExecRequest(rcl.getBestDatacenterId(), rcl.getBestVmId(), rcl);
		setEstimationStatus(STOPPED);
		sendNow(getId(), CloudSimTags.BROKER_ESTIMATE_NEXT_TASK);
	}
	
	private void processPartnerCloudletCancelRequest(SimEvent ev) {
		int[] info = (int[]) ev.getData();
		if (info[1] > 1) { 
			sendNow(info[1], CloudSimTags.DATACENTER_CANCEL_ESTIMATED_TASK, info[2]);
		}
		setEstimationStatus(STOPPED);
		sendNow(getId(), CloudSimTags.BROKER_ESTIMATE_NEXT_TASK);
	}
	
	private void sendCancelRequest(int[] cancel_estimate) {
		sendNow(cancel_estimate[0], CloudSimTags.DATACENTER_CANCEL_ESTIMATED_TASK, cancel_estimate[1]);
	}

	private void sendExecRequest(int targetDatacenterId, int vmId, CustomResCloudlet rcl) {
		rcl.getCloudlet().setVmId(vmId);
//		updatePartnerInformation();
		sendNow(targetDatacenterId, CloudSimTags.DATACENTER_EXEC_TASK, vmId);
	}
	
	private void sendPartnerRequest(Cloudlet cloudlet) {
		CustomResCloudlet rcl = new CustomResCloudlet(cloudlet);
		List<PartnerInfomation> sentPartner = new ArrayList<PartnerInfomation>();
		
		for (PartnerInfomation pi: getPartnersList()) {
			if (pi.numOfTaskCanRequest() >= cloudlet.getCloudletLength()) {
				sendNow(pi.getPartnerId(), CloudSimTags.PARTNER_ESTIMATE_REQUEST, rcl);
				sentPartner.add(pi);
			}
		}
		
		if (sentPartner.isEmpty()) {
			try {
				cloudlet.setCloudletStatus(Cloudlet.FAILED);
			} catch (Exception e) {
				e.printStackTrace();
			}
			sendNow(getId(), CloudSimTags.CLOUDLET_RETURN, cloudlet);
		} else {
			EstimationCloudletOfPartner estPartner = new EstimationCloudletOfPartner(new CustomResCloudlet(cloudlet), 
					sentPartner ,partnersList);
			getEstimateCloudletofParnerMap().put(cloudlet.getCloudletId(), estPartner);
		}
	}
	
	@Override
	protected void processResourceCharacteristicsRequest(SimEvent ev) {
		setDatacenterCharacteristicsList(new HashMap<Integer, DatacenterCharacteristics>());
		buildPartnerInfoList(CloudSim.getEntityList());
//		Log.printLine(CloudSim.clock() + ": " + getName() + ": Cloud Resource List received with "
//				+ getDatacenterIdsList().size() + " resource(s)");
		for (Integer datacenterId : getDatacenterIdsList()) {
			sendNow(datacenterId, CloudSimTags.RESOURCE_CHARACTERISTICS, getId());
		}
	}
	
	/**
	 * Receive request estimate from partner. 
	 * Forward it to add my datacenter for estimating
	 */
	@Override
	public void handlerPartnerCloudletEstimateRequest(SimEvent ev){
		CustomResCloudlet crl = (CustomResCloudlet) ev.getData();
		Cloudlet cl = crl.getCloudlet();

		int partnerId = ev.getSource();
		for (PartnerInfomation pi: getPartnersList()) {
			if (pi.getPartnerId() == partnerId) {
				if (cl.getCloudletLength() > pi.numOfTaskCanSatisfy()) { /* Cannot satisfy -> Refuse incoming request */
					crl.setMaxProcessable(0);
					sendNow(partnerId, CloudSimTags.PARTNER_ESTIMATE_RETURN, crl);
					setEstimationStatus(RUNNING);
				} else {
					this.addCloudletToEstimationList(cl);
				}
			} else {
				continue;
			}
		}
	}
	/**
	 * receive estimate result from partner
	 * @param ev
	 */
	@Override
	protected void processReturnEstimateFromPartner(SimEvent ev) {
		CustomResCloudlet rcl =(CustomResCloudlet) ev.getData();
		Log.printLine(CloudSim.clock() + ": " + getName() + ": Received estimate result from Broker #" + ev.getSource() +"Cloudlet #"+rcl.getCloudletId()+":"+rcl.getMaxProcessable());
		if (getEstimateCloudletofParnerMap().containsKey(rcl.getCloudletId())) {
			EstimationCloudletOfPartner partnerCloudletEstimateList = getEstimateCloudletofParnerMap().get(rcl.getCloudletId());
		
			int[] cancelEstimationResultPartner = partnerCloudletEstimateList.receiveEstimateResult(ev.getSource(), rcl);
			
			if (cancelEstimationResultPartner[0] > -1) {
				sendNow(cancelEstimationResultPartner[0], CloudSimTags.PARTNER_CANCEL_ESTIMATED_TASK, cancelEstimationResultPartner);
			}
			if (partnerCloudletEstimateList.isFinished()) {
				CustomResCloudlet resCloudlet = partnerCloudletEstimateList.getResCloudlet();
				if(partnerCloudletEstimateList.isExecable()){
					PartnerInfomation best  = partnerCloudletEstimateList.getCurrentBestPartner();
					updatePartnerInformation(partnerCloudletEstimateList.getCurrentBestPartner());
					sendNow(partnerCloudletEstimateList.getCurrentBestPartnerId(), CloudSimTags.PARTNER_EXEC, resCloudlet);

					Log.printLine(getName() + ": Send Cloudlet #" + resCloudlet.getCloudletId() + " to Partner #" + partnerCloudletEstimateList.getCurrentBestPartnerId() + " to EXEC");
					Log.printLine(getName()+" Best K ratio:"+best.getkRatio() + " of cloudlet #" + resCloudlet.getCloudletId()+"is partner #"+partnerCloudletEstimateList.getCurrentBestPartnerId());
				} else {
					Log.printLine(CloudSim.clock()+ " Our partner can not EXEC cloudlet #" + resCloudlet.getCloudletId());
					resCloudlet.setCloudletStatus(Cloudlet.FAILED);
					sendNow(getId(), CloudSimTags.CLOUDLET_RETURN, resCloudlet.getCloudlet());
				}
			}
		} 
		else
		{
			Log.printLine("Error in processReturnEstimateFromPartner, clouled return not exist in list");
		}
	}
	
	private void updatePartnerInformation(PartnerInfomation currentBestPartner) {
		if(currentBestPartner.getPartnerId() == -1){
			return;
		}
		for(PartnerInfomation pInfo :partnersList){
			if(pInfo.getPartnerId() == currentBestPartner.getPartnerId()){
				pInfo.setRequested(currentBestPartner.getRequested()+pInfo.getRequested());
				pInfo.updateLenghtRatio(currentBestPartner.getRequested(), 0);
				pInfo.updateKRatio();
//				pInfo.setkRatio(currentBestPartner.getkRatio());
//				Log.printLine("updated partner info");
//				Log.printLine(pInfo.toString());
			}
		}
		
	}
	
	private void updatePartnerInformationByValue(int partnerId, double request,double satify) {
		for(PartnerInfomation pInfo :partnersList){
			if(pInfo.getPartnerId() == partnerId){
				pInfo.updateRequested(request);
				pInfo.updateSatified(satify);
				pInfo.updateLenghtRatio(0, 0);	
				pInfo.updateKRatio();
//				Log.printLine("updated partner info");
//				Log.printLine(pInfo.toString());
			}
		}
		
	}

	public void addDatacenter(int datacenterId) {
		getDatacenterIdsList().add(datacenterId);
	}

	/**
	 * Create list of partner information
	 * @param List o add Entity on system.
	 */
	private void buildPartnerInfoList(List<SimEntity> entityList) {
		//build list partner
		for(SimEntity en: entityList){
			PartnerInfomation partnerInfoItem = null;
			if (en instanceof CustomDatacenterBroker  && en.getId() != getId()) {
				CustomDatacenterBroker partner = (CustomDatacenterBroker) en;
				int partner_size = (int) partner.getVmSize();
				if(Simulate.USER_ALPHA_RATIO){
					double contractRatio = (double)partner_size/getVmSize();
					partnerInfoItem   = new PartnerInfomation(en.getId(), contractRatio, partner_size, this);
				}
				else {
					partnerInfoItem   = new PartnerInfomation(en.getId(), 1, (int)getVmSize(), this);
				}

				Log.printLine(partnerInfoItem.toString());
				this.getPartnersList().add(partnerInfoItem);
			}
			
		}
	}
	
	/**
	 * getter and setter area
	 * @return
	 */

	public int getEstimationStatus() {
		return estimationStatus;
	}


	public void setEstimationStatus(int estimationStatus) {
		this.estimationStatus = estimationStatus;
	}


	public List<Cloudlet> getEstimationList() {
		return estimationList;
	}


	public void setEstimationList(List<Cloudlet> estimationList) {
		this.estimationList = estimationList;
	}

	public Map<Integer, Map<Integer, EstimationCloudletObserve>> getCloudletEstimateObserveMap() {
		return cloudletEstimateObserveMap;
	}

	public void setCloudletEstimateObserveMap(
			Map<Integer, Map<Integer, EstimationCloudletObserve>> cloudletEstimateObserveMap) {
		this.cloudletEstimateObserveMap = cloudletEstimateObserveMap;
	}

	public List<PartnerInfomation> getPartnersList() {
		return partnersList;
	}

	public void setPartnersList(List<PartnerInfomation> partnersList) {
		this.partnersList = partnersList;
	}

	public Map<Integer, EstimationCloudletOfPartner> getEstimateCloudletofParnerMap() {
		return estimateCloudletofParnerMap;
	}

	public void setEstimateCloudletofParnerMap(
			Map<Integer, EstimationCloudletOfPartner> estimateCloudletofParnerMap) {
		this.estimateCloudletofParnerMap = estimateCloudletofParnerMap;
	}

	public double getVmSize() {
		return vmSize;
	}

	public void setVmSize(double vmSize) {
		this.vmSize = vmSize;
	}

	public void appendVmSize(double vmSize) {
		this.vmSize += vmSize;
	}

	public List<ScaleObject> getScaleList() {
		return scaleList;
	}

	public void setScaleList(List<ScaleObject> scaleList) {
		this.scaleList = scaleList;
	}

}
