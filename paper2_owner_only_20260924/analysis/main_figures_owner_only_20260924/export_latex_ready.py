"""Export corrected Figures 3--10 under the exact names in the combined TBS main.tex."""

from pathlib import Path
import hashlib
import zipfile


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "original_style"
DEST = HERE / "latex_ready_original_names"
ARCHIVE = HERE / "Latex_ready_figures_3-10_original_names.zip"

# Keys are the literal \includegraphics file names in the current combined manuscript.
LATEX_NAMES = {
    "Figure_Dwelling_Public_Home_Combined.png": "Figure_03_dwelling_A.png",
    "hexbin_Public_energy_kWh_zoomed.png": "Figure_04_public_spatial_A.png",
    "hexbin_Private_energy_kWh_zoomed.png": "Figure_05_private_spatial_A.png",
    "Figure_Zone_Public_Home_Combined.png": "Figure_06_zone_A.png",
    "newplot.png": "Figure_07_public_sessions_flow_A.png",
    "Combined_Load_Panel_1.png": "Figure_08_access_AB.png",
    "public_demand_2scenarios_hexbin_apartment.png": "Figure_09_apartment_public_AB.png",
    "public_energy_change_by_zone_all_residents_per_fleet_ev.png": "Figure_10_public_energy_by_zone_AB.png",
}


def checksum(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    DEST.mkdir(exist_ok=True)
    assert len(LATEX_NAMES) == len(set(LATEX_NAMES.values())) == 8
    for target_name, source_name in LATEX_NAMES.items():
        source = SOURCE / source_name
        target = DEST / target_name
        data = source.read_bytes()
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"Not a PNG: {source}")
        if target.exists() and target.read_bytes() != data:
            raise ValueError(f"Existing LaTeX-ready file differs; refusing overwrite: {target}")
        if not target.exists():
            target.write_bytes(data)
        assert checksum(target.read_bytes()) == checksum(data)
        print(f"{target_name} <- {source_name} [{checksum(data)[:12]}]")

    with zipfile.ZipFile(ARCHIVE, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name in LATEX_NAMES:
            z.write(DEST / name, arcname=name)
    with zipfile.ZipFile(ARCHIVE) as z:
        assert set(z.namelist()) == set(LATEX_NAMES)
        for name in LATEX_NAMES:
            assert z.read(name) == (DEST / name).read_bytes()
    print(f"Verified eight exact-name PNGs in {ARCHIVE}")


if __name__ == "__main__":
    main()
