 package org.matsim.a_shuai.EVRun_shuai;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargerSpecification;
import org.matsim.contrib.ev.infrastructure.ChargerWriter;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureUtils;
import org.matsim.contrib.ev.infrastructure.ImmutableChargerSpecification;
import org.matsim.contrib.ev.strategic.infrastructure.PublicChargerProvider;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

public class Chargerswriting {

	public static void main(String[] args) throws IOException, CsvException {
		ChargingInfrastructureSpecification infrastructure = ChargingInfrastructureUtils.createChargingInfrastructureSpecification();
        String path = "G:\\Eclipse work_EV\\EV_simulation/chargersV3.xml";
		try {
            // 读取CSV文件
            CSVReader reader = new CSVReader(new FileReader("G:\\Eclipse work_EV\\EV_simulation\\data\\Publiccharging_stationsV1.csv"));
            List<String[]> rows = reader.readAll();
            reader.close();

            AttributesImpl attributes = new AttributesImpl();
            attributes.putAttribute( "sevc:public", Boolean.TRUE);
            // 跳过标题行
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                String id = row[0];
                String isFast = row[3];
                String stationCo = row[4];
                String nearestRoadId = row[5]; // 新增：获取nearest_road_id
                String x = row[6]; // longitude列作为x坐标
                String y = row[7]; // latitude列作为y坐标

                // 解析充电桩数量并过滤无效值
                int count;
                try {
                    count = Integer.parseInt(stationCo);
                } catch (NumberFormatException e) {
                    continue; // 跳过无法解析的行
                }
                if (count <= 0) continue;

                // 确定充电类型
                double chargingSpeed = 50000;
                if ("T".equalsIgnoreCase(isFast)) {
                	chargingSpeed = 200000;
                }
                
                ChargerSpecification specification = ImmutableChargerSpecification.newBuilder()
                		.id(Id.create("charger" + Integer.toString(i), Charger.class))
                		.chargerType("type1")
                		.linkId(Id.createLinkId(nearestRoadId))
                		.plugCount(count)
                		.plugPower(chargingSpeed)
                		.build();
                PublicChargerProvider.setPublic(specification, true);
                infrastructure.addChargerSpecification(specification
                		);   
                
                
            }

            new ChargerWriter(infrastructure.getChargerSpecifications().values().stream()).write(path);
            System.out.println("转换完成！生成的XML文件：charging_stations.xml");

        } catch (Exception e) {
            e.printStackTrace();
        }
		
		
    }
	}


