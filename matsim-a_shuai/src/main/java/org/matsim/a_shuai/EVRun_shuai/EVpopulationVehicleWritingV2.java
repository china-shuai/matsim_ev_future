package org.matsim.a_shuai.EVRun_shuai;

	import com.opencsv.CSVReader;            // 需要 opencsv 依赖
	import org.matsim.api.core.v01.Id;
	import org.matsim.api.core.v01.Scenario;
	import org.matsim.api.core.v01.population.*;
	import org.matsim.core.config.Config;
	import org.matsim.core.config.ConfigUtils;
	import org.matsim.core.population.io.PopulationReader;
	import org.matsim.core.population.io.PopulationWriter;
	import org.matsim.core.scenario.ScenarioUtils;
	import org.matsim.vehicles.*;

	import java.io.FileReader;
	import java.util.*;

	/**
	 * 基于 mapping_ev_driver.csv 指定的 EV 驾驶者名单，为对应 person 分配 EV
	 */
	public class EVpopulationVehicleWritingV2 {
			private static final String STRATEGY_COLUMN = "ev_driver_LDF";
		
	    public static void main(String[] args) throws Exception {
	        Random random = new Random();
	        double socMean = 0.5;
	        double socSd   = 0.1;

	    	  // 文件路径
	        String inputPopulationFile  = "/Users/S4065267/Downloads/同步空间/A_Paper1/Simulation/plan_LDF.xml";
	        String inputVehiclesFile    = "/Users/S4065267/Downloads/同步空间/A_Paper1/Simulation/mode-vehicles.xml";
	        String mappingCsvFile       = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Data/Population10/new model/mapping_ev_driver.csv";
	        String outputPopulationFile = "//Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Data/Population10/new model/ev_population.xml";
	        String outputVehiclesFile   = "/Users/S4065267/Library/CloudStorage/OneDrive-RMITUniversity/2nd Milestone/Paper 1/Data/Population10/new model/ev_vehicles.xml";

	        // 1. 加载指定策略下的 EV 驾驶者 person_id 集合
	        Set<String> evDrivers = loadEvDrivers(mappingCsvFile, STRATEGY_COLUMN);

	        // 2. 读入 population 和 vehicles
	        Config config   = ConfigUtils.createConfig();
	        Scenario sc      = ScenarioUtils.createScenario(config);
	        new PopulationReader(sc).readFile(inputPopulationFile);
	        new MatsimVehicleReader(sc.getVehicles()).readFile(inputVehiclesFile);

	        // 3. 获取车辆类型
	        VehicleType evType  = sc.getVehicles()
	                                .getVehicleTypes()
	                                .get(Id.create("EV_65.0kWh", VehicleType.class));
	        VehicleType carType = sc.getVehicles()
	                                .getVehicleTypes()
	                                .get(Id.create("car", VehicleType.class));

	        // 4. 对于每个 person，只要在 evDrivers 集合里，就分配 EV
	        for (Person person : sc.getPopulation().getPersons().values()) {
	            String pid = person.getId().toString();
	            if (!evDrivers.contains(pid)) {
	                // 不是 EV 驾驶者，跳过
	                continue;
	            }
	            double soc = socMean + socSd * random.nextGaussian();
	            soc = Math.max(0.2, Math.min(1.0, soc));
	            
	            // 创建 EV 车辆并加入场景
	            Id<Vehicle> evVehId = Id.create(pid, Vehicle.class);
	            Vehicle evVeh = VehicleUtils.getFactory().createVehicle(evVehId, evType);
	            evVeh.getAttributes().putAttribute("initialSoc", soc);
	            sc.getVehicles().addVehicle(evVeh);

	            // 给 person 挂载这辆车
	            PersonVehicles pv = new PersonVehicles();
	            pv.addModeVehicle("car", evVehId);
	            person.getAttributes().putAttribute("vehicles", pv);
	            person.getAttributes().putAttribute("sevc:criticalSoc", 0.2);
	            person.getAttributes().putAttribute("wevc:active", Boolean.TRUE);
	        }

	        // 5. 写出结果
	        new PopulationWriter(sc.getPopulation(), sc.getNetwork())
	            .write(outputPopulationFile);
	        new MatsimVehicleWriter(sc.getVehicles())
	            .writeFile(outputVehiclesFile);
	    }


	    /**
	     * 从 CSV 中加载 EV 驾驶者的 person_id 列（假设 CSV 第一列为 person_id，列名同样是 "person_id"）
	     */
	    private static Set<String> loadEvDrivers(String csvFile, String strategyCol) throws Exception {
	        Set<String> evSet = new HashSet<>();
	        try (CSVReader reader = new CSVReader(new FileReader(csvFile))) {
	            String[] header = reader.readNext();
	            int idxPerson = Arrays.asList(header).indexOf("person_id");
	            int idxStrat  = Arrays.asList(header).indexOf(strategyCol);
	            if (idxPerson < 0 || idxStrat < 0) {
	                throw new RuntimeException("CSV 中未找到 person_id 或 " + strategyCol + " 列");
	            }
	            String[] line;
	            while ((line = reader.readNext()) != null) {
	                // 只收集策略列值为 "true"（不区分大小写）的 person_id
	                if ("true".equalsIgnoreCase(line[idxStrat].trim())) {
	                    evSet.add(line[idxPerson].trim());
	                }
	            }
	        }
	        return evSet;
	    }
	}

