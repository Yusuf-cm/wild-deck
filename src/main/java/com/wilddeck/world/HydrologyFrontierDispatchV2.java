package com.wilddeck.world;

import java.util.*;

/**
 * Converts unresolved graph frontier edges into idempotent epoch receipts.
 * One receipt is emitted per unique source->unloaded-destination edge;
 * confluences are NOT collapsed into one ambiguous receipt identity.
 *
 * An unresolved node without a known downstream remains parked at its
 * own address. This class never invents unknown terrain routes.
 */
public final class HydrologyFrontierDispatchV2 {
    private HydrologyFrontierDispatchV2(){}

    public record Transfer(HydrologyTransferLedgerV2.Key key,double flow) {
        public Transfer {
            Objects.requireNonNull(key);
            if(!Double.isFinite(flow)||flow<0)
                throw new IllegalArgumentException("Invalid frontier transfer");
        }
    }

    public record Dispatch(List<Transfer> transfers,
                           Map<GlobalHydrologyAddressV2.Sample,Double> unresolved) {
        public Dispatch {
            transfers=List.copyOf(transfers);
            var sorted=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
                HydrologyFlowGraphV2.ADDRESS_ORDER);
            sorted.putAll(unresolved);
            unresolved=Collections.unmodifiableMap(sorted);
        }

        /** Returns the number of newly accepted transfers (not duplicate replays). */
        public int deliver(HydrologyEpochInboxV2 inbox) {
            Objects.requireNonNull(inbox);
            int accepted=0;
            for(var transfer:transfers)
                if(inbox.receive(transfer.key(),transfer.flow()))accepted++;
            return accepted;
        }
    }

    public static Dispatch export(long epoch,
            Collection<HydrologyFlowGraphV2.Node> sources,
            HydrologyFlowGraphV2.Result solved) {
        if(epoch<0)throw new IllegalArgumentException("Invalid epoch");
        Objects.requireNonNull(sources);
        Objects.requireNonNull(solved);
        var nodes=new TreeMap<GlobalHydrologyAddressV2.Sample,HydrologyFlowGraphV2.Node>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:sources){
            Objects.requireNonNull(node);
            if(nodes.putIfAbsent(node.address(),node)!=null)
                throw new IllegalArgumentException("Duplicate frontier source");
        }
        // Reject a result from another graph, even when its pending endpoints
        // happen to match. This validation costs one deterministic re-solve.
        if(!HydrologyFlowGraphV2.solve(nodes.values()).equals(solved))
            throw new IllegalArgumentException("Frontier graph/result mismatch");
        var transfers=new ArrayList<Transfer>();
        var unresolved=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        var reconstructed=new TreeMap<GlobalHydrologyAddressV2.Sample,Double>(
            HydrologyFlowGraphV2.ADDRESS_ORDER);
        for(var node:nodes.values()){
            if(node.terminal())continue;
            var to=node.downstream();
            if(to!=null&&nodes.containsKey(to))continue;
            var amount=solved.discharge().get(node.address());
            if(amount==null||!Double.isFinite(amount)||amount<0)
                throw new IllegalArgumentException("Missing source discharge");
            var destination=to==null?node.address():to;
            if(to==null)unresolved.put(destination,amount);
            else transfers.add(new Transfer(
                new HydrologyTransferLedgerV2.Key(epoch,node.address(),to),amount));
            reconstructed.merge(destination,amount,(a,b)->{
                double sum=a+b;
                if(!Double.isFinite(sum))throw new IllegalArgumentException("Frontier overflow");
                return sum;
            });
        }
        if(reconstructed.size()!=solved.pendingOutflow().size())
            throw new IllegalArgumentException("Frontier graph/result mismatch");
        for(var entry:reconstructed.entrySet()){
            var actual=solved.pendingOutflow().get(entry.getKey());
            if(actual==null||Math.abs(actual-entry.getValue())>
                1e-10*Math.max(1,Math.max(actual,entry.getValue())))
                throw new IllegalArgumentException("Frontier water mismatch");
        }
        return new Dispatch(transfers,unresolved);
    }
}
