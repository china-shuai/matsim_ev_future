package org.matsim.contrib.ev.behavior;

import java.io.StringWriter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.ev.fleet.*;
import org.matsim.contrib.ev.infrastructure.*;
import org.matsim.contrib.ev.strategic.infrastructure.PersonChargerProvider;
import org.matsim.contrib.ev.withinday.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.*;

/** No controller or transport simulation is executed by these tests. */
public class FutureChargingExposureTest {
    static void eq(double a,double b) {if(Math.abs(a-b)>1e-9||!Double.isFinite(a))throw new AssertionError(a+" != "+b);}
    static void yes(boolean b){if(!b)throw new AssertionError();}
    static FutureChargingBehaviourModel model(double rate) {
        var c=new FutureChargingBehaviourConfigGroup();c.setLatentPublicDemand(true);c.setUnrestrictedLatentPublicDemand(true);
        c.setLatentWorkplaceServiceRate(rate);c.setLatentDestinationServiceRate(rate);
        c.setMinimumLatentActivityChargingDuration(3600);c.setMinimumLatentFastChargingDuration(1800);
        c.setMaximumLatentPublicFastSoc(.8);c.setLatentPublicFastSearchRadius(500);
        var m=new FutureChargingBehaviourModel(c,FutureChargingBehaviourParameters.createDefault());
        m.setLatentPublicFastCandidates(List.of(new Coord(100000,100000)));return m;
    }
    @Test public void coefficientsAndGate() {
        var n=new FutureChargingAutoExposureModule.Counts(10,18,4);
        var c=FutureChargingAutoExposureModule.coefficients(n,2,4);
        eq(c.sStar(),10./32);eq(c.delta(),Math.log(10./22*2));
        eq(c.home()-c.away(),c.delta());eq(2*c.home()+4*c.away(),0);
        var m=model(.3);m.setHomeChargingPreferences(GroupType.APARTMENT,10,18,4,2,4);
        FutureChargingAutoExposureModule.verifyApplied(m,GroupType.APARTMENT,c);
        boolean rejected=false;try{FutureChargingAutoExposureModule.coefficients(n,0,4);}catch(IllegalArgumentException e){rejected=true;}yes(rejected);
        rejected=false;try{new FutureChargingAutoExposureModule.Counts(0,18,4);}catch(IllegalArgumentException e){rejected=true;}yes(rejected);
    }
    @Test public void structuralAvailability() {
        var m=model(.3);var work=FutureChargingActivityLabel.WORK;
        eq(FutureChargingOpportunityRules.expectedAway(m,work,3599,false),0);
        eq(FutureChargingOpportunityRules.expectedAway(m,work,3600,false),.3);
        eq(FutureChargingOpportunityRules.expectedAway(model(.9),work,3600,false),.9);
        eq(FutureChargingOpportunityRules.expectedAway(m,work,3600,true),1); // W+F is ONE activity
        eq(FutureChargingOpportunityRules.expectedAway(m,FutureChargingActivityLabel.SHOP,3600,false),.3);
        eq(FutureChargingOpportunityRules.expectedAway(m,FutureChargingActivityLabel.OTHER,3600,false),0);
        eq(FutureChargingOpportunityRules.expectedAway(m,work,1799,true),0);
        eq(FutureChargingOpportunityRules.expectedAway(m,work,1800,true),1);
        yes(FutureChargingOpportunityRules.feasible(m,work,true,false,.9,1800,true,false,false).isEmpty());
        yes(FutureChargingOpportunityRules.feasible(m,work,true,true,.9,0,false,false,false).contains(FutureChargingSupplyType.FAST));
    }
    @Test public void timingsAndOwnership() throws Exception {
        var config=ConfigUtils.createConfig(new WithinDayEvConfigGroup());config.qsim().setEndTime(30*3600);
        var s=ScenarioUtils.createScenario(config);var pf=s.getPopulation().getFactory();
        var p=pf.createPerson(Id.createPersonId("owner"));s.getPopulation().addPerson(p);WithinDayEvUtils.activate(p);
        p.getAttributes().putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE,true);
        p.getAttributes().putAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE,"apartment");
        var plan=pf.createPlan();p.addPlan(plan);
        var h=pf.createActivityFromLinkId("home",Id.createLinkId("h"));h.setCoord(new Coord(0,0));h.setEndTime(8*3600);plan.addActivity(h);
        var l=pf.createLeg("car");l.setRoutingMode("car");l.setTravelTime(1800);plan.addLeg(l);
        var w=pf.createActivityFromLinkId("work",Id.createLinkId("w"));w.setCoord(new Coord(10,10));w.setEndTime(17*3600);plan.addActivity(w);
        var back=pf.createLeg("car");back.setRoutingMode("car");back.setTravelTime(1800);plan.addLeg(back);
        var end=pf.createActivityFromLinkId("home",Id.createLinkId("h"));end.setCoord(new Coord(0,0));end.setEndTime(23.5*3600);plan.addActivity(end);
        var times=FutureChargingOpportunityRules.timings(plan);
        eq(times.get(w).start(),8.5*3600);eq(times.get(w).duration(),8.5*3600);
        eq(times.get(end).start(),17.5*3600);eq(times.get(end).end(),32*3600); // Paper 3 final Home rule
        var type=VehicleUtils.createVehicleType(Id.create("type",VehicleType.class));ElectricFleetUtils.setElectricVehicleType(type);
        VehicleUtils.setEnergyCapacity(type.getEngineInformation(),105.0);ElectricFleetUtils.setChargerTypes(type,List.of("home"));
        var v=VehicleUtils.createVehicle(Id.createVehicleId("owner_car"),type);ElectricFleetUtils.setInitialSoc(v,.6);
        VehicleUtils.insertVehicleIdsIntoAttributes(p,Map.of("car",v.getId()));
        var fleet=new ElectricFleetSpecificationDefaultImpl();ElectricFleetUtils.createAndAddVehicleSpecificationsFromMatsimVehicles(fleet,List.of(v));
        var infra=new ChargingInfrastructureSpecificationDefaultImpl();
        var charger=ImmutableChargerSpecification.newBuilder().id(Id.create("private",Charger.class)).linkId(Id.createLinkId("h"))
            .chargerType("home").plugPower(7000).plugCount(1).build();
        PersonChargerProvider.setPersonIds(charger,List.of(p.getId()));infra.addChargerSpecification(charger);
        var out=new StringWriter();var e=FutureChargingAutoExposureModule.exposures(s,fleet,infra,model(.3),out).get(GroupType.APARTMENT);
        eq(e[0],1);eq(e[1],.3);yes(out.toString().contains("initial_activity_not_started"));
        PersonChargerProvider.setPersonIds(charger,List.of(Id.createPersonId("someone_else")));
        e=FutureChargingAutoExposureModule.exposures(s,fleet,infra,model(.3),new StringWriter()).get(GroupType.APARTMENT);eq(e[0],0);
        p.getAttributes().putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE,false);
        e=FutureChargingAutoExposureModule.exposures(s,fleet,infra,model(.3),new StringWriter()).get(GroupType.APARTMENT);eq(e[0],0);eq(e[1],0);
        p.getAttributes().putAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE,true);config.qsim().setEndTime(8*3600);
        e=FutureChargingAutoExposureModule.exposures(s,fleet,infra,model(.3),new StringWriter()).get(GroupType.APARTMENT);eq(e[0],0);eq(e[1],0);
    }
    public static void main(String[] args) throws Exception {
        var t=new FutureChargingExposureTest();t.coefficientsAndGate();t.structuralAvailability();t.timingsAndOwnership();
        System.out.println("PASS: coefficient identities, HC gate, invalid E, duration boundaries, coverage, no double count, SoC/mandatory runtime rules, plan times, overnight Home, ownership, horizon, initial and HC=0 exclusions");
    }
}
