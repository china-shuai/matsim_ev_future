"""Compare Figure 6 zone cohorts with the corrected A-run initial SOC input."""
import csv
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


HERE = Path(__file__).resolve().parent
FIGURES = HERE.parent / "figures"
VEHICLES = HERE.parent.parent / "mainline_owner_only_20260923/inputs/06_ev_vehicles_random_soc.xml"
ZONES = ("Inner", "Middle", "Outer")

initial_soc = {}
for _, element in ET.iterparse(VEHICLES, events=("end",)):
    if element.tag.rsplit("}", 1)[-1] != "vehicle":
        continue
    for child in element.iter():
        if child.tag.rsplit("}", 1)[-1] == "attribute" and child.get("name") == "initialSoc":
            initial_soc[element.get("id")] = float(child.text)
            break
    element.clear()

population = {}
with (FIGURES / "population_A_zones.csv").open(newline="") as handle:
    for row in csv.DictReader(handle):
        population[row["person_id"]] = row["zone"]

if set(initial_soc) != set(population):
    raise ValueError(f"Vehicle/population ID mismatch: {len(initial_soc)} vs {len(population)}")

cohorts = defaultdict(set)
for person, zone in population.items():
    if zone in ZONES:
        cohorts[(zone, "All EVs")].add(person)

with (FIGURES / "sessions_A.csv").open(newline="") as handle:
    for row in csv.DictReader(handle):
        zone = row["zone"]
        if zone not in ZONES:
            continue
        person = row["person_id"]
        location = row["location"]
        hour = int(row["hour"])
        cohorts[(zone, "Any charging")].add(person)
        cohorts[(zone, location)].add(person)
        if zone == "Outer" and location == "Public" and hour in (8, 11, 12):
            cohorts[(zone, f"Public {hour:02d}:00")].add(person)
        if zone == "Inner" and location == "Home" and hour == 8:
            cohorts[(zone, "Home 08:00")].add(person)

out = HERE / "zone_initial_soc.csv"
with out.open("w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow(("home_zone", "cohort", "vehicles", "mean_initial_soc", "median_initial_soc"))
    for zone in ZONES:
        for cohort in ("All EVs", "Any charging", "Public", "Home", "Public 08:00", "Public 11:00", "Public 12:00", "Home 08:00"):
            members = cohorts.get((zone, cohort))
            if not members:
                continue
            vals = sorted(initial_soc[p] for p in members)
            n = len(vals)
            median = vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2
            writer.writerow((zone, cohort, n, sum(vals) / n, median))

print(out)
with out.open() as handle:
    print(handle.read())
