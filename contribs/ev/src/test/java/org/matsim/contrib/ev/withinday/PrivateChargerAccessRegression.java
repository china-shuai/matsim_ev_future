package org.matsim.contrib.ev.withinday;

import java.lang.reflect.Method;
import com.google.common.collect.ImmutableMap;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.ev.charging.ChargingLogic;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargerSpecification;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructure;
import org.matsim.contrib.ev.infrastructure.ImmutableChargerSpecification;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/** Deterministic executable regression checks; also runnable against the old jar. */
public class PrivateChargerAccessRegression {
    private static int checks;
    private record TestCharger(ChargerSpecification specification, Coord coord) implements Charger {
        public ChargerSpecification getSpecification() { return specification; }
        public Coord getCoord() { return coord; }
        public Id<Charger> getId() { return specification.getId(); }
        public Attributes getAttributes() { return specification.getAttributes(); }
        public ChargingLogic getLogic() { return null; }
        public Link getLink() { return null; }
        public String getChargerType() { return specification.getChargerType(); }
        public double getPlugPower() { return specification.getPlugPower(); }
        public int getPlugCount() { return specification.getPlugCount(); }
    }
    private static Charger charger(String id, double x, double power, String owner, boolean publicFlag) {
        AttributesImpl a = new AttributesImpl();
        if (owner != null) a.putAttribute("sevc:persons", owner);
        if (publicFlag) a.putAttribute("sevc:public", true);
        return new TestCharger(ImmutableChargerSpecification.newBuilder().id(Id.create(id, Charger.class))
                .linkId(Id.createLinkId("link")).chargerType("type1").plugPower(power).plugCount(1)
                .attributes(a).build(), new Coord(x, 0));
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static Charger select(Charger current, boolean fast, Charger... candidates) throws Exception {
        var b = ImmutableMap.<Id<Charger>, Charger>builder();
        b.put(current.getId(), current);
        for (Charger c : candidates) b.put(c.getId(), c);
        ChargingInfrastructure infrastructure = () -> b.buildOrThrow();
        var engine = new WithinDayEvEngine(new WithinDayEvConfigGroup(), null, null, null,
                null, null, null, null, null, null, null, null, infrastructure, null, null, null);
        Method m = WithinDayEvEngine.class.getDeclaredMethod("selectPreferredCharger",
                ChargingSlot.class, Charger.class, double.class, boolean.class);
        m.setAccessible(true);
        return (Charger) m.invoke(engine, null, current, fast ? 50000.0 : 22000.0, fast);
    }
    private static void denied(Charger c, String person) {
        boolean rejected = false;
        try { WithinDayEvEngine.requirePrivateChargerOwnership(c, Id.createPersonId(person)); }
        catch (IllegalStateException e) { rejected = e.getMessage().contains("PRIVATE_CHARGER_ACCESS_VIOLATION"); }
        check(rejected, "deny non-owner or unbound private " + c.getId());
    }
    public static void main(String[] args) throws Exception {
        Charger current = charger("publicDC", 0, 150000, null, true);
        Charger privateAC = charger("privateCharger_owner", 1, 10000, "owner", false);
        Charger publicAC = charger("publicAC", 100, 22000, null, true);
        if (Boolean.getBoolean("expectBug")) {
            check(select(current, false, privateAC, publicAC) == privateAC, "old jar reproduces private AC substitution");
            return;
        }
        check(select(current, false, privateAC, publicAC) == publicAC, "AC chooses farther public, not nearest private");
        check(select(current, false, privateAC) == null, "AC no public option never falls back to private");
        Charger privateDC = charger("privateCharger_fast", 1, 100000, "other", false);
        Charger publicDC = charger("publicDC2", 100, 100000, null, true);
        check(select(publicAC, true, privateDC, publicDC) == publicDC, "DCFC excludes private fast chargers");
        Charger ambiguous = charger("privateCharger_both", 1, 10000, "owner", true);
        check(!WithinDayEvEngine.isPublicPreferenceCharger(ambiguous), "owner attribute overrides public tag");
        check(!WithinDayEvEngine.isPublicPreferenceCharger(privateAC), "current private AC is not public-compatible");
        check(WithinDayEvEngine.isPublicPreferenceCharger(publicAC), "public AC retained");
        check(WithinDayEvEngine.isPublicPreferenceCharger(publicDC), "public DCFC retained");
        check(!WithinDayEvEngine.isPublicPreferenceCharger(charger("unmarked", 0, 10000, null, false)), "unmarked infrastructure not public substitution");
        WithinDayEvEngine.requirePrivateChargerOwnership(privateAC, Id.createPersonId("owner"));
        check(true, "own home charger permitted");
        WithinDayEvEngine.requirePrivateChargerOwnership(publicAC, Id.createPersonId("visitor"));
        check(true, "public charger permitted for visitor");
        denied(privateAC, "visitor"); denied(ambiguous, "visitor");
        denied(charger("privateCharger_missing", 0, 10000, null, false), "visitor");
        check(!WithinDayEvEngine.isPublicPreferenceCharger(null), "null candidate rejected");
        System.out.println("ALL " + checks + " CHECKS PASSED");
    }
}
