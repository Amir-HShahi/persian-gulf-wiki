package com.persiangulfwiki.core.subject.service;

// Keys of the derived fields V27 seeds, which the read side computes instead of reading facts
// (decision 14), and of the fields population_density is computed from (V23). A derived definition
// with any other key has nothing computing it and shows no value.
final class DerivedAttributes {

    static final String POPULATION_DENSITY = "population_density";
    static final String HAS_360_TOUR = "has_360_tour";

    static final String POPULATION = "population";
    static final String AREA = "area";

    private DerivedAttributes() {
    }
}
