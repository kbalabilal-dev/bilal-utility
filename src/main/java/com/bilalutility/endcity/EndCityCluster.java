package com.bilalutility.endcity;

import java.util.List;

/** A bucket of ship-bearing End Cities. */
public record EndCityCluster(int x, int z, int count, double distance, List<EndCity> cities) {}
