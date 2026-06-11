package org.matsim.contrib.ev.behavior;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Guice module binding a configured {@link ChargingBehaviourModel} so that within-day charging injects
 * the behaviour-driven decision model.
 */
public class ChargingBehaviourModule extends AbstractModule {
	@Override
	public void install() {
		bind(ChargingBehaviourModel.class).toProvider(ChargingBehaviourModelProvider.class).in(Singleton.class);
		bind(ChargingDecisionStrategy.class).to(LegacyChargingDecisionStrategy.class).in(Singleton.class);
		addControlerListenerBinding().to(ChargingBehaviourCalibrationListener.class).in(Singleton.class);
	}

	@Singleton
	private static final class ChargingBehaviourModelProvider implements Provider<ChargingBehaviourModel> {
		private static final Logger LOGGER = LogManager.getLogger(ChargingBehaviourModelProvider.class);

		private final Config config;

		@Inject
		private ChargingBehaviourModelProvider(Config config) {
			this.config = Objects.requireNonNull(config, "config");
		}

		@Override
		public ChargingBehaviourModel get() {
			ChargingBehaviourConfigGroup cfg = ConfigUtils.addOrGetModule(config,
					ChargingBehaviourConfigGroup.GROUP_NAME, ChargingBehaviourConfigGroup.class);

			ChargingBehaviourParameters parameters = ChargingBehaviourParameters.createDefault();
			parameters.setLearningRate(cfg.getLearningRate());
			parameters.setLearningRateDecay(cfg.getLearningRateDecay());
			parameters.setAcReferencePower(cfg.getAcReferencePower());
			parameters.setDcfcReferencePower(cfg.getDcfcReferencePower());

			List<LocationPreferenceRecord> locationPreferenceRecords = parseLocationPreferences(cfg, parameters);

			for (GroupType group : GroupType.values()) {
				ChargingBehaviourParameters.GroupParameters groupParams = parameters.getGroupParameters(group);
				groupParams.setLambda(cfg.getLambda(group));
				groupParams.setRhoHome(cfg.getRhoHome(group));
				groupParams.setRhoAway(cfg.getRhoAway(group));
				groupParams.setRealFrequency(cfg.getRealFrequency(group));
			}

			applyTimePreferences(cfg, parameters);

			ChargingBehaviourModel model = new ChargingBehaviourModel(parameters);
			applyLocationPreferences(cfg, model, locationPreferenceRecords);
			applyDestinationPreferences(cfg, model);

			return model;
		}

		private void applyTimePreferences(ChargingBehaviourConfigGroup configGroup,
				ChargingBehaviourParameters parameters) {
			URL url = configGroup.getTimePreferenceFileURL(config.getContext());
			if (url == null) {
				return;
			}

			LOGGER.info("Loading charging time preferences from {}", url);

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
				String line;
				boolean headerProcessed = false;

				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}

					if (!headerProcessed && line.toLowerCase(Locale.ROOT).contains("group")) {
						headerProcessed = true;
						continue;
					}
					headerProcessed = true;

					String[] tokens = line.split("[,;\\t]");
					if (tokens.length < 3) {
						throw new IllegalArgumentException(
								"Invalid time preference entry (need group,timeBand,value): " + line);
					}

					GroupType group = GroupType.valueOf(tokens[0].trim().toUpperCase(Locale.ROOT));
					TimeBand timeBand = TimeBand.valueOf(tokens[1].trim().toUpperCase(Locale.ROOT));
					double value = Double.parseDouble(tokens[2].trim());

					parameters.getGroupParameters(group).setTimePreference(timeBand, value);
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read charging behaviour time preferences from " + url, e);
			}
		}

		private List<LocationPreferenceRecord> parseLocationPreferences(ChargingBehaviourConfigGroup configGroup,
				ChargingBehaviourParameters parameters) {
			URL url = configGroup.getLocationPreferenceFileURL(config.getContext());
			if (url == null) {
				return List.of();
			}

			LOGGER.info("Loading charging location preferences from {}", url);

			List<LocationPreferenceRecord> records = new ArrayList<>();

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
				String line;
				boolean headerProcessed = false;

				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}

					if (!headerProcessed && line.toLowerCase(Locale.ROOT).contains("group")) {
						headerProcessed = true;
						continue;
					}
					headerProcessed = true;

					String[] tokens = line.split("[,;\\t]");
					if (tokens.length < 6) {
						throw new IllegalArgumentException(
								"Invalid location preference entry (need group,nHome,nDest,nFast,eHome,eAway[,realFrequency]): "
										+ line);
					}

					GroupType group = GroupType.valueOf(tokens[0].trim().toUpperCase(Locale.ROOT));
					double nHome = Double.parseDouble(tokens[1].trim());
					double nDest = Double.parseDouble(tokens[2].trim());
					double nFast = Double.parseDouble(tokens[3].trim());
					double eHome = Double.parseDouble(tokens[4].trim());
					double eAway = Double.parseDouble(tokens[5].trim());

					if (tokens.length >= 7) {
						double realFrequency = Double.parseDouble(tokens[6].trim());
						parameters.getGroupParameters(group).setRealFrequency(realFrequency);
					}

					records.add(new LocationPreferenceRecord(group, nHome, nDest, nFast, eHome, eAway));
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read charging behaviour location preferences from " + url, e);
			}

			return records;
		}

		private void applyLocationPreferences(ChargingBehaviourConfigGroup configGroup, ChargingBehaviourModel model,
				List<LocationPreferenceRecord> records) {
			if (records.isEmpty()) {
				return;
			}

			LOGGER.info("Applying charging location preferences from {}", configGroup.getLocationPreferenceFile());

			for (LocationPreferenceRecord record : records) {
				model.setHomeChargingPreferences(record.group(), record.nHome, record.nDest, record.nFast,
						record.eHome, record.eAway);
			}
		}

		private void applyDestinationPreferences(ChargingBehaviourConfigGroup configGroup, ChargingBehaviourModel model) {
			URL url = configGroup.getDestinationPreferenceFileURL(config.getContext());
			if (url == null) {
				return;
			}

			LOGGER.info("Loading charging destination preferences from {}", url);

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
				String line;
				boolean headerProcessed = false;

				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}

					if (!headerProcessed && line.toLowerCase(Locale.ROOT).contains("group")) {
						headerProcessed = true;
						continue;
					}
					headerProcessed = true;

					String[] tokens = line.split("[,;\\t]");
					if (tokens.length < 3) {
						throw new IllegalArgumentException(
								"Invalid destination preference entry (need group,alphaDestAc,alphaDcfc): " + line);
					}

					GroupType group = GroupType.valueOf(tokens[0].trim().toUpperCase(Locale.ROOT));
					double alphaDestAc = Double.parseDouble(tokens[1].trim());
					double alphaDcfc = Double.parseDouble(tokens[2].trim());

					model.setDestinationLogOdds(group, alphaDestAc, alphaDcfc);
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read charging behaviour destination preferences from " + url, e);
			}
		}

		private record LocationPreferenceRecord(GroupType group, double nHome, double nDest, double nFast,
				double eHome, double eAway) {
		}
	}
}
