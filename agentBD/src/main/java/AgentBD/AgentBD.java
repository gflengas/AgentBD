package AgentBD;

import java.util.Random;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

import negotiator.issue.*;
import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.Accept;
import negotiator.actions.Action;
import negotiator.actions.Offer;
import negotiator.parties.AbstractNegotiationParty;
import negotiator.parties.NegotiationInfo;
import negotiator.utility.AbstractUtilitySpace;
import negotiator.timeline.TimeLineInfo;

public class AgentBD extends AbstractNegotiationParty{

	HashMap<Bid, Double> bids = new HashMap<Bid, Double>();
	private Bid ourLastBid = null;
	private Bid lastReceivedBid = null;
	private AbstractUtilitySpace utilSpace = null;
	private double df = 0; // here we store the discount factor of the domain
	private TimeLineInfo timeline = null;
	private HashMap<String, Double> numberOfBids = new HashMap<String, Double>(); // number of bid offered to us 
	private HashMap<String, Double> sumOfBids = new HashMap<String, Double>(); //sum of bids' utilities
	private HashMap<String, Double> average_util = new HashMap<String, Double>(); //average utility(for us) of opponent's bids
	private HashMap<String, Bid> best_bid = new HashMap<String, Bid>();
	private HashMap<String, Double> utilities = new HashMap<String, Double>();
	private ArrayList<Bid> bids_array = new ArrayList<Bid>();
	private int nOfRounds;
    private static double PERCENTAGE_T_OFFER_MAX_U = 0.2D; // Percentage of time in which we'll just keep offering the maximum utility bid
	private double avgRoundTime;
	private ArrayList<String> opponents = new ArrayList<String>();
	private Bid max_utility_bid = null;
	
	private boolean foundAllBids = false;
	private HashMap<Bid, Integer> popularBids = new HashMap<Bid, Integer>();
	private HashMap<Issue, HashMap<Value, Integer>> oppFrequency = new HashMap<Issue, HashMap<Value, Integer>>();
	private ArrayList<Double> valueWeights = new ArrayList<Double>();
	private ArrayList<Double> issueWeights = new ArrayList<Double>();
	private Bid myLowestBid = null;
	
	@Override
	public void init(NegotiationInfo info) {

		super.init(info);
		
		System.out.println("Discount Factor is " + info.getUtilitySpace().getDiscountFactor());
		System.out.println("Reservation Value is " + info.getUtilitySpace().getReservationValueUndiscounted());

		// if you need to initialize some variables, please initialize them
		// below
		utilSpace = info.getUtilitySpace();
		df = utilSpace.getDiscountFactor();
		timeline = info.getTimeline();
		nOfRounds = 0;
		initValues();
		initIssues();

	}
	
	public Action chooseAction(List<Class<? extends Action>> list) {

        // for first 20% of time, offer max utility bid , try for 10-20-30
        if (isMaxUtilityOfferTime()) {
            Bid maxUtilityBid = null;
            try {
                maxUtilityBid = utilSpace.getMaxUtilityBid();
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Exception: cannot generate max utility bid");
            }
            return new Offer(getPartyId(), maxUtilityBid);
        }

        System.out.println("Last received bid had a utility of " + getUtility(this.lastReceivedBid) + " for me." );

        
        //accept now!
        if(acceptImmediate(lastReceivedBid)) return new Accept(getPartyId(), lastReceivedBid);
        
     // Generate an acceptable bid
        Bid ourBid = makeBid();
        
        // Check if we should accept the latest offer given the bid we're proposing
        if (acceptBid(ourBid, lastReceivedBid)) {
            return new Accept(getPartyId(), this.lastReceivedBid);
        }

        // Offer our bid
        return new Offer(getPartyId(), ourBid);
    }

	/**
	 * All offers proposed by the other parties will be received as a message.
	 * You can use this information to your advantage, for example to predict
	 * their utility.
	 *
	 * @param sender
	 *            The party that did the action. Can be null.
	 * @param action
	 *            The action that party did.
	 */
	@Override
	public void receiveMessage(AgentID sender, Action action) {
		super.receiveMessage(sender, action);
		if(sender != null) {
			if(!opponents.contains(sender.getName())) {
				opponents.add(sender.getName());
				numberOfBids.put(sender.getName(), 0d);
				sumOfBids.put(sender.getName(), 0d);
				average_util.put(sender.getName(), 0d);
				utilities.put(sender.getName(), 0d);
			}
		}
		if (action instanceof Offer) {
			Bid ReceivedBid = ((Offer) action).getBid();
			if(lastReceivedBid != null) {
				updateValueFrequency(ReceivedBid);
				updateIssueChanges(ReceivedBid); 
			}
			updateValueWeights();
			updateIssueWeights();
			lastReceivedBid = ReceivedBid;
			numberOfBids.replace(sender.getName(), numberOfBids.get(sender.getName()) + 1);
			sumOfBids.replace(sender.getName(), sumOfBids.get(sender.getName()) + utilSpace.getUtility(lastReceivedBid));
			average_util.replace(sender.getName(), sumOfBids.get(sender.getName()) / numberOfBids.get(sender.getName()));
			utilities.replace(sender.getName(), utilities.get(sender.getName()) + Math.pow(utilSpace.getUtility(lastReceivedBid), utilSpace.getUtility(lastReceivedBid)));
			if(!best_bid.containsKey(sender.getName())) {
				best_bid.put(sender.getName(), lastReceivedBid);
			}else if(utilSpace.getUtility(lastReceivedBid) > utilSpace.getUtility(best_bid.get(sender.getName()))) {
				best_bid.replace(sender.getName(), lastReceivedBid);
			}
		}else if(action instanceof Accept) {
			Bid AcceptedBid = ((Accept) action).getBid();
			//System.out.println("Accepted bid is null ...");
			// get the most accepted/popular bid for future use 
			// also update the opponent model tables again 
			// this way we put more value on popular bids
			// this can possibly get us a more ideal bid
			if(AcceptedBid != null) {
			if(popularBids.containsKey(AcceptedBid)){ 
				popularBids.replace(AcceptedBid, popularBids.get(AcceptedBid) + 1);
				if(lastReceivedBid != null) {
					updateValueFrequency(AcceptedBid);
					updateIssueChanges(AcceptedBid); 
				}
				updateValueWeights();
				updateIssueWeights();
			}else{
				popularBids.put(AcceptedBid, 1);
			}}
		}
	}



	public String getDescription() {
		return "Bad";
	}
	
	/***************************************************************************************
	 * Bid Strategy
	 ***************************************************************************************/

	public Bid makeBid() {
		double threshold; 
		Random rnd = new Random();
		double min = Double.MAX_VALUE;
		for(int o = 0; o < opponents.size(); o++) {
			threshold = getThreshold(opponents.get(o));
			if(threshold < min) min = threshold;
		}
		threshold = min;
		if(timeline.getTime() > 0.985){
			Bid optimal = getMostPopularBid();
			if(optimal != null){
				if(utilSpace.getUtility(optimal) > utilSpace.getReservationValue()){
					return optimal;
				}
			}
		}
		Bid bid = new Bid(utilSpace.getDomain());
		ArrayList<Bid> noms = new ArrayList<Bid>();
		int i = 0;

		do {
			if(foundAllBids) {
				bid = bids_array.get(i);
			}else {
				bid = generateRandomBid();
			}
			if(utilSpace.getUtility(bid) > threshold && utilSpace.getUtility(bid) < 1.1 * threshold){
				noms.add(bid);
			}
			i++;
			if(i >= bids.size()) {
				try {
					if (noms.isEmpty()) {
						if(max_utility_bid == null) max_utility_bid = utilSpace.getMaxUtilityBid();
						bid = max_utility_bid;
					}
					break;
				}catch (Exception e) {
					e.printStackTrace();
				}
			}
		}while(true);
		
		if (noms.size() > 0) bid = noms.get(rnd.nextInt(noms.size()));
		double max = -1;
		Bid max_bid = bid;
		double opp_util = 0;
		// which of the target bids has the biggest utility for our opponent??
		for(int n = 0; n < noms.size(); n++) {
			opp_util = getOppUtility(noms.get(n));
			if(opp_util > max) {
				max = opp_util;
				max_bid = noms.get(n);
			}
		}
		if(myLowestBid == null) myLowestBid = max_bid;
		if(utilSpace.getUtility(myLowestBid) >= utilSpace.getUtility(max_bid)) myLowestBid = max_bid;
		return max_bid;
	}
	
	
	public double getThreshold(String sender) {
		double sum2 = utilities.get(sender);
		double var = sum2 / numberOfBids.get(sender) - Math.pow(average_util.get(sender), average_util.get(sender));
		double sigma = Math.sqrt(var);
		double d = sigma;
		double emax = average_util.get(sender) + (1 - average_util.get(sender)) * d;
		double rate;
		if(timeline.getTime() < 0.85) {
			rate = 3;
		}else if(timeline.getTime() < 0.95) {
			rate = 2;
		}else {
			rate = 1.5;
		}
		double target = 1 - (1 - emax) * Math.pow(timeline.getTime() * df, rate);
		return target;
	}
	
	// get most popular bid 
	// returns null if there is no such bid
	public Bid getMostPopularBid(){
		Bid optimal  = null;
		int max = -1;
		for(int i = 0; i < bids_array.size(); i++){
			if(popularBids.containsKey(bids_array.get(i))){
				if(popularBids.get(bids_array.get(i)) >  max){
					max = popularBids.get(bids_array.get(i));
					optimal = bids_array.get(i);
				}
			}
		}
		return optimal;
	}	
	
	/*
	 * get estimated opponent's utility for a bid 
	 * using the issue and value weights
	 */
	public double getOppUtility(Bid bid) {
		double utility = 0;
		List<Issue> issues = utilSpace.getDomain().getIssues();
		Value v = null;
		
		int old_counter = 0;
		int counter = 0;
		
		for(int j = 0; j < issues.size(); j++) {
			v = bid.getValue(issues.get(j).getNumber());
			ArrayList<Value> values = getValues(issues.get(j));
			double vweight = 0;
			old_counter = counter;
			for(int i = 0; i < values.size(); i++) {
				counter++;
				if(v.equals(values.get(i))) {
					vweight = valueWeights.get(old_counter + i);
				}
			}
			double iweight = issueWeights.get(j);
			if(issues.size() == 1) iweight = 1;
			utility += (iweight * (vweight));
		}
		return (utility);
	}
	
	/********************************************************************************
	 * Opponent Model
	 ********************************************************************************/
	
	/*
	 * initialize the value frequency and valueWeights tables
	 */
	public void initValues() {
		List<Issue> issues = utilSpace.getDomain().getIssues();
		for(Issue issue:issues) { // for each issue
			ArrayList<Value> values = getValues(issue);
			HashMap<Value, Integer> temp = new HashMap<Value, Integer>();
			for(Value value:values) { // for each value
				valueWeights.add(0d);
				temp.put(value, 0);
			}
			oppFrequency.put(issue, temp);
		}	
	}
	
	/*
	 * get all possible values for an issue
	 */
	ArrayList<Value> getValues(Issue issue) {
        ArrayList<Value> values = new ArrayList<Value>();
        switch (issue.getType()) {
            case UNKNOWN:
                break;
            case DISCRETE:
                List<ValueDiscrete> valuesDis = ((IssueDiscrete) issue).getValues();
                values.addAll(valuesDis);
                break;
            case INTEGER:
                int min_value = ((IssueInteger) issue).getLowerBound();
                int max_value = ((IssueInteger) issue).getUpperBound();
                for (int j = min_value; j <= max_value; j++) {
                    values.add(new ValueInteger(j));
                }
                break;
            case REAL:
                double min = ((IssueReal) issue).getLowerBound();
                double max = ((IssueReal) issue).getUpperBound();
                for (double j = min; j <= max; j++) {
                    values.add(new ValueReal(j));
                }
                break;
            case OBJECTIVE:
                break;
            default:
                System.out.println(" Failed to get the values.");
        }
        return values;
    }

	/* update value frequency when a new bid has been offered to us
	 * increment when a value remains unchanged
	 */
	public void updateValueFrequency(Bid bid) {
		for(Issue issue:utilSpace.getDomain().getIssues()) { // for each issue
			Value curr_value = bid.getValue(issue.getNumber()); // current value
			Value old_value = lastReceivedBid.getValue(issue.getNumber()); // old value
			HashMap<Value, Integer> temp = oppFrequency.get(issue);
			// increment value weights each time they remain unchanged
			if(old_value.equals(curr_value)) temp.replace(curr_value, temp.get(curr_value) + 1);
			oppFrequency.replace(issue, temp);
		}
	}
	
	/*
	 * update the new value weights with respect to value frequency
	 * normalize for each issue by dividing with the max value
	 */
	public void updateValueWeights() {
		// find max in oppFrequency
		double max;
		ArrayList<Integer> temp = new ArrayList<Integer>();
		int counter = 0;
		for(Issue issue:utilSpace.getDomain().getIssues()) { // for each issue
			ArrayList<Value> values = getValues(issue);
			max = - 10;
			int old_counter = counter;
			for(Value value:values) {
				counter++;
				temp.add(oppFrequency.get(issue).get(value));
				if(oppFrequency.get(issue).get(value) > max) {
					max = oppFrequency.get(issue).get(value); // max in this issue
				}
			}
			if(max != 0) {
				for(int i = old_counter + 1; i < counter; i++) {
					valueWeights.set(i, temp.get(i).doubleValue() / max);
				}
			}
		}
	}
	
	/*
	 * initialize the issue weights table
	 */
	public void initIssues() {
		double length = utilSpace.getDomain().getIssues().size();
		for(Issue issue:utilSpace.getDomain().getIssues()) {
			issueWeights.add((1 / length));
		}
	}
	
	/*
	 * every time an issue remains unchanged increment by 0.2
	 */
	public void updateIssueChanges(Bid bid) {
		int index = 0;
		for(Issue issue:utilSpace.getDomain().getIssues()) {
			
			Value value = bid.getValue(issue.getNumber());
			Value old_value = lastReceivedBid.getValue(issue.getNumber());
			if(value.equals(old_value)) { // remains unchanged
				issueWeights.set(index, issueWeights.get(index) + 0.2);
			}
			index++;
		}
	}
	
	/* 
	 * normalize the issue weights
	 */
	public void updateIssueWeights() {
		// calculate sum 
		double sum = 0;
		for(int i = 0; i < issueWeights.size(); i++) {
			sum += issueWeights.get(i); // sum of issue weights
		}
		// normalize the weights so they sum to 1
		for(int i = 0; i < issueWeights.size(); i++) {
			issueWeights.set(i, issueWeights.get(i) / sum);
		}	
	}
	
	/******************************************************************************
	 * Accept Strategy
	 *****************************************************************************/
	private boolean isMaxUtilityOfferTime() {
        return getTimeLine().getTime() < this.PERCENTAGE_T_OFFER_MAX_U;
    }
	
	public boolean acceptImmediate(Bid oppbid) {
		if(getRoundsRemaining() < 1.5 && utilSpace.getUtility(oppbid) > utilSpace.getReservationValue()) { // running out of time, accept
			return true;
		}
		if(myLowestBid != null) { // opponent's bid is better than the lowest bid we've offered
			if (utilSpace.getUtility(myLowestBid) <= utilSpace.getUtility(oppbid)){
				return true;
			}
		}
		if(popularBids.containsKey(oppbid)){
			if(timeline.getTime() > 0.985) return true;
		}
		return false;
	}
	
	/*
	 * accepting conditions
	 */
	public boolean acceptBid(Bid mybid, Bid oppbid) {
		if(utilSpace.getUtility(oppbid) <= utilSpace.getReservationValue()) { // we get more utility by denying
			return false;
		}
		if(utilSpace.getUtility(oppbid) >= utilSpace.getUtility(mybid)) { // opponent's bid is better than ours
			return true;
		}
		if(ourLastBid != null){ // opponent's bid is better than our last bid
			if (utilSpace.getUtility(ourLastBid) <= utilSpace.getUtility(oppbid)){
				return true;
			}
		}
		return false; 
	}
	
	
	/* 
	 * here we estimate the number of rounds remaining by calculating the average time
	 * needed for a round to complete , if there is a discount factor lower than 1 we 
	 * set the deadline based on the df (if df == 0.7 the "deadline" is deadline*0.7)
	 */
	public double getRoundsRemaining() {  
		nOfRounds++;
		avgRoundTime = timeline.getTime() / nOfRounds;
		double roundsRemaining = (timeline.getTotalTime() * df - timeline.getTime()) / avgRoundTime;
		return roundsRemaining;
	}

}