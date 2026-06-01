package io.github.svenwirz.config;

/**
 * Parallelitäts-Limit pro Processor-Typ (R15). {@code null} bedeutet "unbegrenzt"
 * auf der jeweiligen Ebene; die striktere effektive Grenze gilt.
 */
public class ProcessorLimit {

    /** Maximal gleichzeitig laufende Tasks dieses Typs pro Knoten. */
    private Integer perNode;

    /** Maximal gleichzeitig laufende Tasks dieses Typs cluster-weit. */
    private Integer clusterWide;

    public Integer getPerNode() {
        return perNode;
    }

    public void setPerNode(Integer perNode) {
        this.perNode = perNode;
    }

    public Integer getClusterWide() {
        return clusterWide;
    }

    public void setClusterWide(Integer clusterWide) {
        this.clusterWide = clusterWide;
    }
}
