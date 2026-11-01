package com.wilddeck.world;

import java.util.*;

/**
 * Conservative, opt-in lake storage transfer after an authoritative terrain
 * re-solve. The caller must supply old and new CLOSED-sphere basin plans for
 * the same sampling grid. An unchanged canonical basin anchor identifies the
 * same lake; unverified split/merge identity changes fail closed while wet.
 *
 * A smaller reservoir releases the displaced volume to the NEW verified spill
 * target. The returned flux is not injected automatically: the caller must
 * route it exactly once, without also counting the old lake storage as rain.
 */
public final class HydrologyLakeGeometryTransitionV2 {
    private HydrologyLakeGeometryTransitionV2() {}

    public record Result(HydrologyLakeStateV2.State state,
                         Map<GlobalHydrologyAddressV2.Sample,Double> overflow,
                         double initialStorage,double finalStorage,
                         double released) {
        public Result {
            Objects.requireNonNull(state);
            var ordered=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            ordered.putAll(overflow);
            overflow=Collections.unmodifiableMap(ordered);
        }
    }

    public static Result reconcile(
            HydrologyLakeStateV2.State previous,
            HydrologySphericalLakeBasinsV2.Plan oldPlan,
            HydrologySphericalLakeBasinsV2.Plan newPlan) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(oldPlan);
        Objects.requireNonNull(newPlan);
        var oldLakes=index(oldPlan);
        var newLakes=index(newPlan);
        var oldMembership=memberships(oldLakes);
        var newMembership=memberships(newLakes);
        // A wet lake cannot silently split into two identities, or merge
        // with another wet lake, even if one original anchor survives.
        for(var old:oldLakes.entrySet()) {
            if(previous.volumes().getOrDefault(old.getKey(),0.0)==0)continue;
            for(var cell:old.getValue().cells()) {
                var newOwner=newMembership.get(cell);
                if(newOwner!=null&&!newOwner.equals(old.getKey()))
                    throw new IllegalArgumentException("Wet lake identity split");
            }
        }
        for(var next:newLakes.entrySet()) {
            for(var cell:next.getValue().cells()) {
                var oldOwner=oldMembership.get(cell);
                if(oldOwner!=null&&!oldOwner.equals(next.getKey())&&
                   previous.volumes().getOrDefault(oldOwner,0.0)>0)
                    throw new IllegalArgumentException("Wet lake identity merge");
            }
        }
        var stored=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var overflow=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        double initial=0,remaining=0,released=0;
        for(var entry:previous.volumes().entrySet()) {
            var anchor=entry.getKey();
            double volume=entry.getValue();
            var old=oldLakes.get(anchor);
            if(old==null||volume>old.reservoir().capacity())
                throw new IllegalArgumentException(
                    "Unverified previous lake storage or basin geometry");
            initial=add(initial,volume);
            if(!newLakes.containsKey(anchor)&&volume>0)
                throw new IllegalArgumentException(
                    "Wet lake split, merged or disappeared without identity reconciliation");
        }
        for(var entry:newLakes.entrySet()) {
            var anchor=entry.getKey();
            var lake=entry.getValue();
            double volume=previous.volumes().getOrDefault(anchor,0.0);
            double retained=Math.min(volume,lake.reservoir().capacity());
            double excess=volume-retained;
            stored.put(anchor,retained);
            remaining=add(remaining,retained);
            released=add(released,excess);
            if(excess>0)
                overflow.merge(lake.spillTarget(),excess,
                    HydrologyLakeGeometryTransitionV2::add);
        }
        double tolerance=Math.max(1e-10*initial,1e-10);
        if(Math.abs(initial-add(remaining,released))>tolerance)
            throw new IllegalArgumentException("Lake geometry transition lost water");
        return new Result(new HydrologyLakeStateV2.State(previous.world(),stored),
            overflow,initial,remaining,released);
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
                       HydrologySphericalLakeBasinsV2.Lake> index(
            HydrologySphericalLakeBasinsV2.Plan plan) {
        var lakes=new TreeMap<GlobalHydrologyAddressV2.Sample,
                              HydrologySphericalLakeBasinsV2.Lake>(
                                  HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var lake:plan.lakes()) {
            Objects.requireNonNull(lake);
            if(lakes.putIfAbsent(lake.anchor(),lake)!=null)
                throw new IllegalArgumentException("Duplicate global lake anchor");
        }
        return lakes;
    }

    private static Map<GlobalHydrologyAddressV2.Sample,
                       GlobalHydrologyAddressV2.Sample> memberships(
            Map<GlobalHydrologyAddressV2.Sample,
                HydrologySphericalLakeBasinsV2.Lake> lakes) {
        var owners=new HashMap<GlobalHydrologyAddressV2.Sample,
                               GlobalHydrologyAddressV2.Sample>();
        for(var lake:lakes.values())
            for(var cell:lake.cells())
                if(owners.putIfAbsent(cell,lake.anchor())!=null)
                    throw new IllegalArgumentException("Overlapping lake cells");
        return owners;
    }

    private static double add(double a,double b) {
        double result=a+b;
        if(!Double.isFinite(result)||result<0)
            throw new IllegalArgumentException("Lake volume overflow");
        return result;
    }
}
