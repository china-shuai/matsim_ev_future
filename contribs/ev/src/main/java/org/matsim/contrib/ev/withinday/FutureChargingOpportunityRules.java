package org.matsim.contrib.ev.withinday;

import java.util.*;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.ev.behavior.*;
import org.matsim.core.router.TripStructureUtils;

/** Paper 3 rules shared by runtime eligibility and ex-ante exposure accounting. */
public final class FutureChargingOpportunityRules {
    private FutureChargingOpportunityRules() {}
    public static EnumSet<FutureChargingSupplyType> feasible(FutureChargingBehaviourModel model,
            FutureChargingActivityLabel label, boolean hc, boolean mandatory, double soc, double duration,
            boolean nearFast, boolean workplaceAvailable, boolean destinationAvailable) {
        if (model == null || (label == FutureChargingActivityLabel.HOME && hc))
            return EnumSet.noneOf(FutureChargingSupplyType.class);
        boolean fastEligible = mandatory || ((nearFast && (!Double.isFinite(soc)
            || soc <= model.getMaximumLatentPublicFastSoc())) && duration >= model.getMinimumLatentFastChargingDuration());
        if (label == FutureChargingActivityLabel.HOME)
            return fastEligible ? EnumSet.of(FutureChargingSupplyType.FAST) : EnumSet.noneOf(FutureChargingSupplyType.class);
        var feasible = model.isUnrestrictedLatentPublicDemand()
            ? EnumSet.noneOf(FutureChargingSupplyType.class)
            : model.createFeasiblePublicSupplyTypes(label, hc, mandatory);
        if (model.isUnrestrictedLatentPublicDemand() && fastEligible) feasible.add(FutureChargingSupplyType.FAST);
        if (!fastEligible) feasible.remove(FutureChargingSupplyType.FAST);
        if (mandatory && feasible.isEmpty()) feasible.add(FutureChargingSupplyType.FAST);
        boolean activityEligible = duration >= model.getMinimumLatentActivityChargingDuration();
        if (activityEligible && label == FutureChargingActivityLabel.WORK && workplaceAvailable)
            feasible.add(FutureChargingSupplyType.WORKPLACE);
        if (activityEligible && model.isDestinationChargingActivity(label) && destinationAvailable)
            feasible.add(FutureChargingSupplyType.DESTINATION);
        return feasible;
    }
    /** Expected availability BEFORE SoC/mandatory/acceptance decisions; no random draws. */
    public static double expectedAway(FutureChargingBehaviourModel model, FutureChargingActivityLabel label,
            double duration, boolean nearFast) {
        double w=model.getLatentWorkplaceServiceRate(), d=model.getLatentDestinationServiceRate();
        double result=0;
        for(int i=0;i<2;i++) for(int j=0;j<2;j++) {
            double probability=(i==1?w:1-w)*(j==1?d:1-d);
            if(!feasible(model,label,true,false,Double.NaN,duration,nearFast,i==1,j==1).isEmpty()) result+=probability;
        }
        return result;
    }
    public record Timing(double start, double end) { public double duration(){return Math.max(0,end-start);} }
    public static Map<Activity,Timing> timings(Plan plan) {
        Map<Activity,Timing> result=new IdentityHashMap<>();
        if(plan==null) return result;
        Activity last=null;
        for(var e:plan.getPlanElements()) if(e instanceof Activity a && a.getType()!=null
                && !WithinDayEvEngine.isManagedActivityType(a.getType()) && !TripStructureUtils.isStageActivityType(a.getType())) last=a;
        double overnight=Double.NaN;
        if(!plan.getPlanElements().isEmpty() && plan.getPlanElements().getFirst() instanceof Activity first
                && first.getEndTime().isDefined() && Double.isFinite(first.getEndTime().seconds())) overnight=first.getEndTime().seconds()+86400;
        double now=0;
        for(var e:plan.getPlanElements()) {
            if(e instanceof Activity a) {
                double start=a.getStartTime().isDefined()?a.getStartTime().seconds():now, end=Double.NaN;
                if(a==last && Double.isFinite(overnight)) end=Math.max(overnight,Math.max(start,now));
                else if(a.getEndTime().isDefined()) end=a.getEndTime().seconds();
                else if(a.getMaximumDuration().isDefined()) end=start+a.getMaximumDuration().seconds();
                result.put(a,new Timing(start,Double.isFinite(end)?end:start));
                now=Math.max(now,Double.isFinite(end)?end:start);
            } else if(e instanceof Leg l) {
                now=Math.max(now,l.getDepartureTime().isDefined()?l.getDepartureTime().seconds():now);
                double travel=l.getTravelTime().isDefined()?l.getTravelTime().seconds():
                    l.getRoute()!=null&&l.getRoute().getTravelTime().isDefined()?l.getRoute().getTravelTime().seconds():Double.NaN;
                if(Double.isFinite(travel)&&travel>0) now+=travel;
            }
        }
        return result;
    }
    public static Map<Activity,Double> activityDurations(Plan plan) {
        Map<Activity,Double> result=new IdentityHashMap<>();
        timings(plan).forEach((a,t)->result.put(a,t.duration())); return result;
    }
}
