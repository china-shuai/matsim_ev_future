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

import org.matsim.api.core.v01.Coord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Guice module for the Paper 3 sequential H/W/D/F charging behaviour model.
 */
public class FutureChargingBehaviourModule extends AbstractModule {
	@Override
	public void install() {
		bind(FutureChargingBehaviourModel.class).toProvider(FutureChargingBehaviourModelProvider.class).in(Singleton.class);
		bind(ChargingDecisionStrategy.class).to(FutureChargingDecisionStrategy.class).in(Singleton.class);
		addControllerListenerBinding().to(FutureChargingBehaviourCalibrationListener.class).in(Singleton.class);
	}

	@Singleton
	private static final class FutureChargingBehaviourModelProvider implements Provider<FutureChargingBehaviourModel> {
		private static final Logger LOGGER = LogManager.getLogger(FutureChargingBehaviourModelProvider.class);

		private final Config config;

		@Inject
		private FutureChargingBehaviourModelProvider(Config config) {
			this.config = Objects.requireNonNull(config, "config");
		}

		@Override
		public FutureChargingBehaviourModel get() {
			FutureChargingBehaviourConfigGroup cfg = ConfigUtils.addOrGetModule(config,
					FutureChargingBehaviourConfigGroup.GROUP_NAME, FutureChargingBehaviourConfigGroup.class);

			FutureChargingBehaviourParameters parameters = FutureChargingBehaviourParameters.createDefault();
			parameters.setLearningRate(cfg.getLearningRate());
			parameters.setLearningRateDecay(cfg.getLearningRateDecay());
			parameters.setAcReferencePower(cfg.getAcReferencePower());
			parameters.setDcfcReferencePower(cfg.getDcfcReferencePower());

			for (GroupType group : GroupType.values()) {
				FutureChargingBehaviourParameters.GroupParameters groupParams = parameters.getGroupParameters(group);
				groupParams.setLambda(cfg.getLambda(group));
				groupParams.setRealFrequency(cfg.getRealFrequency(group));
				groupParams.setRhoHome(cfg.getRhoHome(group));
				groupParams.setRhoAway(cfg.getRhoAway(group));
			}

			applyTimePreferences(cfg.getTimePreferenceFileURL(config.getContext()), parameters);
			applyLocationPreferences(cfg.getLocationPreferenceFileURL(config.getContext()), parameters);

			FutureChargingBehaviourModel model = new FutureChargingBehaviourModel(cfg, parameters);
			applyPublicStartSocDistributions(cfg.getPublicStartSocDistributionFileURLs(config.getContext()), model);
			applyLatentPublicFastCandidates(cfg.getLatentPublicFastCandidateFileURL(config.getContext()), model);
			applyDestinationPreferences(cfg.getDestinationPreferenceFileURL(config.getContext()), model);
			applySupplyPreferences(cfg.getSupplyPreferenceFileURL(config.getContext()), model);
			return model;
		}

		private void applyLatentPublicFastCandidates(URL url, FutureChargingBehaviourModel model) {
			if (url == null) {
				return;
			}
			LOGGER.info("Loading latent public FAST candidate locations from {}", url);
			List<Coord> candidates = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
				String line;
				int xColumn = -1;
				int yColumn = -1;
				boolean headerProcessed = false;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] tokens = line.split("[,;\\t]", -1);
					if (!headerProcessed) {
						for (int i = 0; i < tokens.length; i++) {
							String column = tokens[i].trim().toLowerCase(Locale.ROOT);
							if ("x".equals(column)) {
								xColumn = i;
							} else if ("y".equals(column)) {
								yColumn = i;
							}
						}
						if (xColumn < 0 || yColumn < 0) {
							throw new IllegalArgumentException(
									"Latent public FAST candidate file must contain x and y columns: " + url);
						}
						headerProcessed = true;
						continue;
					}
					if (xColumn >= tokens.length || yColumn >= tokens.length) {
						continue;
					}
					double x = Double.parseDouble(tokens[xColumn].trim());
					double y = Double.parseDouble(tokens[yColumn].trim());
					if (Double.isFinite(x) && Double.isFinite(y)) {
						candidates.add(new Coord(x, y));
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read latent public FAST candidates from " + url, e);
			}
			model.setLatentPublicFastCandidates(candidates);
			LOGGER.info("Loaded {} latent public FAST candidate locations; search radius = {} m",
					candidates.size(), model.getLatentPublicFastSearchRadius());
		}

		private void applyPublicStartSocDistributions(List<URL> urls, FutureChargingBehaviourModel model) {
			if (urls.isEmpty()) {
				return;
			}
			int[] counts = new int[10];
			int records = 0;
			for (URL url : urls) {
				LOGGER.info("Loading public charging start-SOC distribution from {}", url);
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
					String line;
					int chargingPercentColumn = -1;
					boolean headerProcessed = false;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.isEmpty() || line.startsWith("#")) {
							continue;
						}
						String[] tokens = line.split("[,;\\t]", -1);
						if (!headerProcessed) {
							for (int i = 0; i < tokens.length; i++) {
								if ("chargingpercent".equals(tokens[i].trim().toLowerCase(Locale.ROOT))) {
									chargingPercentColumn = i;
									break;
								}
							}
							if (chargingPercentColumn >= 0) {
								headerProcessed = true;
								continue;
							}
							chargingPercentColumn = tokens.length - 1;
							headerProcessed = true;
						}
						if (chargingPercentColumn >= tokens.length) {
							continue;
						}
						double soc = Double.parseDouble(tokens[chargingPercentColumn].trim());
						if (soc < 0.0 || soc > 1.0 || !Double.isFinite(soc)) {
							continue;
						}
						int bin = Math.min(9, Math.max(0, (int) Math.floor(soc * 10.0)));
						counts[bin]++;
						records++;
					}
				} catch (IOException e) {
					throw new RuntimeException("Failed to read public charging start-SOC distribution from " + url, e);
				}
			}
			if (records == 0) {
				LOGGER.warn("No valid public charging start-SOC observations found; latent public demand is not SOC-weighted");
				return;
			}
			int max = 0;
			for (int count : counts) {
				max = Math.max(max, count);
			}
			double[] weights = new double[counts.length];
			for (int i = 0; i < counts.length; i++) {
				weights[i] = max > 0 ? (double) counts[i] / max : 1.0;
			}
			model.setPublicStartSocWeights(weights);
			LOGGER.info("Loaded {} public charging start-SOC observations; normalized bin weights: {}",
					records, java.util.Arrays.toString(weights));
		}

		private void applyTimePreferences(URL url, FutureChargingBehaviourParameters parameters) {
			if (url == null) {
				return;
			}
			LOGGER.info("Loading future charging time preferences from {}", url);
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
						throw new IllegalArgumentException("Invalid time preference entry: " + line);
					}
					GroupType group = parseGroup(tokens[0]);
					TimeBand timeBand = TimeBand.valueOf(tokens[1].trim().toUpperCase(Locale.ROOT));
					parameters.getGroupParameters(group).setTimePreference(timeBand, Double.parseDouble(tokens[2].trim()));
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read future charging time preferences from " + url, e);
			}
		}

		private void applyLocationPreferences(URL url, FutureChargingBehaviourParameters parameters) {
			if (url == null) {
				return;
			}
			LOGGER.info("Loading future charging location preferences from {}", url);
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
						throw new IllegalArgumentException("Invalid location preference entry: " + line);
					}
					GroupType group = parseGroup(tokens[0]);
					double nHome = Double.parseDouble(tokens[1].trim());
					double nDest = Double.parseDouble(tokens[2].trim());
					double nFast = Double.parseDouble(tokens[3].trim());
					double eHome = Double.parseDouble(tokens[4].trim());
					double eAway = Double.parseDouble(tokens[5].trim());
					if (tokens.length >= 7) {
						parameters.getGroupParameters(group).setRealFrequency(Double.parseDouble(tokens[6].trim()));
					}
					applyHomePreferences(parameters.getGroupParameters(group), nHome, nDest, nFast, eHome, eAway);
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read future charging location preferences from " + url, e);
			}
		}

		private void applyHomePreferences(FutureChargingBehaviourParameters.GroupParameters parameters, double nHome,
				double nDest, double nFast, double eHome, double eAway) {
			if (nHome < 0 || nDest < 0 || nFast < 0 || eHome <= 0 || eAway <= 0) {
				return;
			}
			double countsTotal = nHome + nDest + nFast;
			if (countsTotal <= 0) {
				return;
			}
			double sStar = nHome / countsTotal;
			double deltaRho = Math.log((sStar / (1.0 - sStar)) * (eAway / eHome));
			double wHome = eHome / (eHome + eAway);
			parameters.setRhoHome((1.0 - wHome) * deltaRho);
			parameters.setRhoAway(-wHome * deltaRho);
		}

		private void applyDestinationPreferences(URL url, FutureChargingBehaviourModel model) {
			if (url == null) {
				return;
			}
			LOGGER.info("Loading future charging destination preferences from {}", url);
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
						throw new IllegalArgumentException("Invalid destination preference entry: " + line);
					}
					GroupType group = parseGroup(tokens[0]);
					model.setSupplyPreference(group, FutureChargingSupplyType.DESTINATION,
							Double.parseDouble(tokens[1].trim()));
					model.setSupplyPreference(group, FutureChargingSupplyType.FAST, Double.parseDouble(tokens[2].trim()));
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read future charging destination preferences from " + url, e);
			}
		}

		private void applySupplyPreferences(URL url, FutureChargingBehaviourModel model) {
			if (url == null) {
				return;
			}
			LOGGER.info("Loading future charging H/W/D/F supply preferences from {}", url);
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
				String line;
				String header = null;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					if (header == null && line.toLowerCase(Locale.ROOT).contains("group")) {
						header = line.toLowerCase(Locale.ROOT);
						continue;
					}
					String[] tokens = line.split("[,;\\t]");
					if (tokens.length < 5) {
						throw new IllegalArgumentException("Invalid supply preference entry: " + line);
					}
					GroupType group = parseGroup(tokens[0]);
					if (header != null && header.contains("alpha")) {
						model.setSupplyPreference(group, FutureChargingSupplyType.HOME, Double.parseDouble(tokens[1].trim()));
						model.setSupplyPreference(group, FutureChargingSupplyType.WORKPLACE,
								Double.parseDouble(tokens[2].trim()));
						model.setSupplyPreference(group, FutureChargingSupplyType.DESTINATION,
								Double.parseDouble(tokens[3].trim()));
						model.setSupplyPreference(group, FutureChargingSupplyType.FAST, Double.parseDouble(tokens[4].trim()));
					} else {
						double nHome = Double.parseDouble(tokens[1].trim());
						double nWork = Double.parseDouble(tokens[2].trim());
						double nDest = Double.parseDouble(tokens[3].trim());
						double nFast = Double.parseDouble(tokens[4].trim());
						double eps = 0.5;
						model.setSupplyPreference(group, FutureChargingSupplyType.HOME, Math.log((nHome + eps) / (nFast + eps)));
						model.setSupplyPreference(group, FutureChargingSupplyType.WORKPLACE,
								Math.log((nWork + eps) / (nFast + eps)));
						model.setSupplyPreference(group, FutureChargingSupplyType.DESTINATION,
								Math.log((nDest + eps) / (nFast + eps)));
						model.setSupplyPreference(group, FutureChargingSupplyType.FAST, 0.0);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to read future charging supply preferences from " + url, e);
			}
		}

		private GroupType parseGroup(String raw) {
			String key = raw.replace("\uFEFF", "").trim().toUpperCase(Locale.ROOT);
			return GroupType.valueOf(key);
		}
	}
}
