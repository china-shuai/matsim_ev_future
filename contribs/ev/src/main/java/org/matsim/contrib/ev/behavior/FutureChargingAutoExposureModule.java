package org.matsim.contrib.ev.behavior;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.ev.fleet.ElectricFleetSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.ev.strategic.infrastructure.PersonChargerProvider;
import org.matsim.contrib.ev.withinday.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.vehicles.VehicleUtils;

/** Paper 3 only. Ex-ante expected opportunities, calculated once after initial routing. */
public final class FutureChargingAutoExposureModule extends AbstractModule {
    @Override public void install() {
        bind(PrepareForSimImpl.class);
        bind(Updater.class).in(Singleton.class);
        bind(PrepareForSim.class).to(AutoPrepare.class).in(Singleton.class);
    }
    public static final class AutoPrepare implements PrepareForSim {
        private final PrepareForSimImpl delegate;
        private final Provider<Updater> updater;
        private boolean prepared;
        @Inject public AutoPrepare(PrepareForSimImpl delegate, Provider<Updater> updater) {
            this.delegate=delegate; this.updater=updater;
        }
        @Override public void run() {
            if(prepared) throw new IllegalStateException("Paper 3 exposure was already prepared");
            delegate.run(); updater.get().run(); prepared=true;
        }
    }
    public record Counts(double home,double dest,double fast) {
        public Counts {
            if(!Double.isFinite(home)||!Double.isFinite(dest)||!Double.isFinite(fast)
                    ||home<=0||dest<0||fast<0||dest+fast<=0||!Double.isFinite(home+dest+fast))
                throw new IllegalArgumentException("Positive finite Home/Away survey counts are required");
        }
    }
    public record Coefficients(double sStar,double delta,double home,double away) {}
    public static Coefficients coefficients(Counts n,double home,double away) {
        if(!Double.isFinite(home)||!Double.isFinite(away)||home<=0||away<=0)
            throw new IllegalArgumentException("Both E_home and E_away must be positive and finite; stale E cannot be reused");
        double delta=Math.log(n.home/(n.dest+n.fast))+Math.log(away/home), w=home/(home+away);
        return new Coefficients(n.home/(n.home+n.dest+n.fast),delta,(1-w)*delta,-w*delta);
    }
    public static EnumMap<GroupType,Counts> readCounts(FutureChargingBehaviourConfigGroup cfg,Scenario scenario) throws IOException {
        var url=cfg.getLocationPreferenceFileURL(scenario.getConfig().getContext());
        if(url==null) throw new IllegalArgumentException("Automatic exposure requires a location-preference CSV");
        var result=new EnumMap<GroupType,Counts>(GroupType.class);
        try(var reader=new BufferedReader(new InputStreamReader(url.openStream(),StandardCharsets.UTF_8))) {
            for(String line;(line=reader.readLine())!=null;) {
                line=line.trim(); if(line.isEmpty()||line.startsWith("#"))continue;
                String[] x=line.split("[,;\\t]",-1);
                if(x[0].trim().equalsIgnoreCase("group"))continue;
                if(x.length<6)throw new IllegalArgumentException("Expected group,nHome,nDest,nFast,eHome,eAway[,frequency]");
                var g=GroupType.valueOf(x[0].trim().toUpperCase(Locale.ROOT));
                var n=new Counts(Double.parseDouble(x[1].trim()),Double.parseDouble(x[2].trim()),Double.parseDouble(x[3].trim()));
                if(result.put(g,n)!=null)throw new IllegalArgumentException("Duplicate group "+g);
            }
        }
        if(result.size()!=GroupType.values().length)throw new IllegalArgumentException("All three survey groups must be supplied");
        return result;
    }
    static boolean hasHome(Person p) {
        Object v=p.getAttributes().getAttribute(WithinDayEvEngine.HOME_CHARGER_ATTRIBUTE);
        if(v==null)v=p.getAttributes().getAttribute("hasHomeCharger");
        return v instanceof Boolean b?b:v instanceof String s&&Boolean.parseBoolean(s);
    }
    static GroupType group(Person p) {
        Object v=p.getAttributes().getAttribute(WithinDayEvEngine.DWELLING_TYPE_ATTRIBUTE);
        if(v==null)v=PopulationUtils.getSubpopulation(p);
        if(v==null)throw new IllegalArgumentException("Missing dwelling group: "+p.getId());
        return switch(v.toString().toLowerCase(Locale.ROOT)) {
            case "apartment","multi_family","multi-family" -> GroupType.APARTMENT;
            case "house_with_pv","housepv","pv_house","house-with-pv","house_with_solar","house-solar" -> GroupType.HOUSE_WITH_PV;
            case "house_no_pv","house","single_family","single-family","house_without_pv","house-no-pv" -> GroupType.HOUSE_NO_PV;
            default -> throw new IllegalArgumentException("Unknown dwelling group: "+v);
        };
    }
    /** Pure opportunity calculation; does not run the controller, select activities, or draw random numbers. */
    public static EnumMap<GroupType,double[]> exposures(Scenario scenario,ElectricFleetSpecification fleet,
            ChargingInfrastructureSpecification infrastructure,FutureChargingBehaviourModel model,Writer audit) throws IOException {
        if(!model.isLatentPublicDemandEnabled())throw new IllegalArgumentException("Paper 3 automatic E requires its latent public demand mode");
        String mode=WithinDayEvConfigGroup.get(scenario.getConfig()).getCarMode();
        double horizon=scenario.getConfig().qsim().getEndTime().seconds();
        if(!Double.isFinite(horizon)||horizon<=0)throw new IllegalArgumentException("Finite positive simulation horizon required");
        Set<String> owners=new HashSet<>();
        for(var c:infrastructure.getChargerSpecifications().values()) {
            Object raw=c.getAttributes().getAttribute(PersonChargerProvider.PERSONS_CHARGER_ATTRIBUTE);
            if(raw instanceof String ids)for(String id:ids.split(","))if(!id.isBlank())owners.add(id.trim());
        }
        var result=new EnumMap<GroupType,double[]>(GroupType.class);
        for(var g:GroupType.values())result.put(g,new double[2]);
        var finder=new ChargingSlotFinder(scenario,mode);
        audit.write("person,group,activity_index,activity,location,start_seconds,end_seconds,duration_seconds,near_fast,opportunity_weight,exclusion\n");
        // Stable summation order and no mutation of selected plans.
        var persons=new ArrayList<>(scenario.getPopulation().getPersons().values());
        persons.sort(Comparator.comparing(p->p.getId().toString()));
        for(var person:persons) {
            if(!WithinDayEvUtils.isActive(person)||!hasHome(person)||!VehicleUtils.hasVehicleId(person,mode))continue;
            var v=fleet.getVehicleSpecifications().get(VehicleUtils.getVehicleId(person,mode));
            if(v==null||!Double.isFinite(v.getBatteryCapacity())||v.getBatteryCapacity()<=0)continue;
            Plan plan=person.getSelectedPlan();
            if(plan==null||plan.getPlanElements().stream().noneMatch(e->e instanceof Leg l&&mode.equals(l.getMode())))continue;
            var g=group(person);
            Set<Activity> homeStarts=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var c:finder.findActivityBased(person,plan))homeStarts.add(c.startActivity());
            var timing=FutureChargingOpportunityRules.timings(plan);
            for(int i=0;i<plan.getPlanElements().size();i++) {
                if(!(plan.getPlanElements().get(i) instanceof Activity a))continue;
                if(a.getType()==null||WithinDayEvEngine.isManagedActivityType(a.getType())||TripStructureUtils.isStageActivityType(a.getType()))continue;
                var label=FutureChargingActivityLabel.fromActivityType(a.getType());
                boolean home=label==FutureChargingActivityLabel.HOME;
                var t=timing.get(a);double weight=0;boolean near=false;
                String reason=i==0?"initial_activity_not_started":
                    !Double.isFinite(t.start())||t.start()>=horizon?"outside_horizon":
                    !Double.isFinite(t.duration())||t.duration()<=0?"nonpositive_duration":"included";
                if(reason.equals("included")) {
                    if(home) {
                        if(!homeStarts.contains(a))reason="no_vehicle_parking_candidate";
                        else if(!owners.contains(person.getId().toString()))reason="no_owned_home_charger";
                        else weight=1;
                    } else {
                        near=model.hasLatentPublicFastCandidateNear(PopulationUtils.decideOnCoordForActivity(a,scenario));
                        weight=FutureChargingOpportunityRules.expectedAway(model,label,t.duration(),near);
                        if(weight==0)reason="no_ordinary_public_supply";
                    }
                }
                result.get(g)[home?0:1]+=weight;
                audit.write(csv(person.getId().toString())+","+g+","+i+","+csv(a.getType())+","+(home?"home":"away")+","+
                    t.start()+","+t.end()+","+t.duration()+","+near+","+weight+","+reason+"\n");
            }
        }
        return result;
    }
    public static void verifyApplied(FutureChargingBehaviourModel model,GroupType g,Coefficients c) {
        var h=new FutureChargingActivity(FutureChargingActivityLabel.HOME,TimeBand.EVENING_16_22,.6,true,EnumSet.of(FutureChargingSupplyType.HOME));
        var a=new FutureChargingActivity(FutureChargingActivityLabel.WORK,TimeBand.EVENING_16_22,.6,true,EnumSet.of(FutureChargingSupplyType.WORKPLACE));
        double dh=model.computeChargingUtility(new Agent(g,true),h)-model.computeChargingUtility(new Agent(g,false),h);
        double da=model.computeChargingUtility(new Agent(g,true),a)-model.computeChargingUtility(new Agent(g,false),a);
        if(Math.abs(dh-c.home)>1e-9||Math.abs(da-c.away)>1e-9)throw new IllegalStateException("Applied rho mismatch for "+g);
    }
    @Singleton public static final class Updater {
        private final Scenario scenario;
        private final ElectricFleetSpecification fleet;
        private final ChargingInfrastructureSpecification infrastructure;
        private final FutureChargingBehaviourModel model;
        private final OutputDirectoryHierarchy output;
        @Inject public Updater(Scenario scenario,ElectricFleetSpecification fleet,ChargingInfrastructureSpecification infrastructure,
                FutureChargingBehaviourModel model,OutputDirectoryHierarchy output) {
            this.scenario=scenario;this.fleet=fleet;this.infrastructure=infrastructure;this.model=model;this.output=output;
        }
        public void run() {
            try {
                var cfg=ConfigUtils.addOrGetModule(scenario.getConfig(),FutureChargingBehaviourConfigGroup.class);
                var counts=readCounts(cfg,scenario);
                Path dir=Path.of(output.getOutputPath());Files.createDirectories(dir);
                EnumMap<GroupType,double[]> exposure;
                try(var w=Files.newBufferedWriter(dir.resolve("paper3_auto_exposure_candidates.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                    exposure=exposures(scenario,fleet,infrastructure,model,w);
                }
                var values=new EnumMap<GroupType,Coefficients>(GroupType.class);
                for(var g:GroupType.values())values.put(g,coefficients(counts.get(g),exposure.get(g)[0],exposure.get(g)[1]));
                try(var w=Files.newBufferedWriter(dir.resolve("paper3_auto_exposure_parameters.csv"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
                    w.write("group,nHome,nWorkplacePlusDestination,nFast,eHome,eAway,s_star,delta_rho,rho_home,rho_away,model_probe_verified\n");
                    for(var g:GroupType.values()) {
                        var n=counts.get(g);var e=exposure.get(g);var c=values.get(g);
                        model.setHomeChargingPreferences(g,n.home,n.dest,n.fast,e[0],e[1]);
                        verifyApplied(model,g,c);
                        w.write(g+","+n.home+","+n.dest+","+n.fast+","+e[0]+","+e[1]+","+c.sStar+","+c.delta+","+c.home+","+c.away+",true\n");
                    }
                }
                Files.writeString(dir.resolve("paper3_auto_exposure_definition.txt"),DEFINITION,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            } catch(IOException e) {throw new UncheckedIOException("Paper 3 automatic exposure failed",e);}
        }
    }
    public static final String DEFINITION="""
        Paper 3: calculated once after initial plan preparation/routing, before iteration 0; frozen thereafter.
        Only active EV agents with HC=1, a valid electric vehicle and a charging-mode leg enter E.
        Initial activity, stage/managed activities, nonpositive duration and starts outside the horizon are excluded.
        Home requires a runtime parking-candidate start and a charger owned by the person.
        Final-activity timing follows the Paper 3 overnight rule; no initial Home charging is enabled.
        Away is the expected count of ordinary feasible activities under the scenario W/D service rates and PFC spatial/duration filters.
        One activity counts at most once even if multiple supply types are available. E_away may be fractional.
        W/D stochastic coverage is integrated out (not sampled); no random stream is advanced.
        E is ex-ante structural availability, NOT realised sessions or an SoC-conditioned opportunity count.
        No SoC, mandatory override, activity selection, acceptance, queue or energy filters enter E.
        nDest in the input CSV means Workplace + Destination for Home-versus-Away; the four-way supply model is unchanged.
        Input E fields are ignored in automatic mode; applied values are in paper3_auto_exposure_parameters.csv.
        The location correction does not guarantee realised Home shares equal stated survey shares.
        """;
    private static String csv(String s) {return "\""+s.replace("\"","\"\"")+"\"";}
}
