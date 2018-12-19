/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2018 Queen Mary University of London.
 * Copyright (c) 2012-2016 Charité Universitätsmedizin Berlin and Genome Research Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.monarchinitiative.exomiser.core.model.pathogenicity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

/**
 * Container for Pathogenicity data about a variant.
 *
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 * @since 3.0.0
 */
public class PathogenicityData {

    private static final PathogenicityData EMPTY_DATA = new PathogenicityData(ClinVarData.empty(), Collections.emptyList());

    private final ClinVarData clinVarData;
    private final Map<PathogenicitySource, PathogenicityScore> pathogenicityScores;

    public static PathogenicityData of(PathogenicityScore pathScore) {
        return of(ClinVarData.empty(), Collections.singletonList(pathScore));
    }

    public static PathogenicityData of(PathogenicityScore... pathScore) {
        return of(ClinVarData.empty(), Arrays.asList(pathScore));
    }

    public static PathogenicityData of(Collection<PathogenicityScore> pathScores) {
        return of(ClinVarData.empty(), pathScores);
    }

    /**
     * @since 10.1.0
     */
    public static PathogenicityData of(ClinVarData clinVarData, PathogenicityScore pathScore) {
        return of(clinVarData, Collections.singletonList(pathScore));
    }

    /**
     * @since 10.1.0
     */
    public static PathogenicityData of(ClinVarData clinVarData, PathogenicityScore... pathScore) {
        return of(clinVarData, Arrays.asList(pathScore));
    }

    /**
     * @since 10.1.0
     */
    public static PathogenicityData of(ClinVarData clinVarData, Collection<PathogenicityScore> pathScores) {
        if (clinVarData.isEmpty() && pathScores.isEmpty()) {
            return EMPTY_DATA;
        }
        return new PathogenicityData(clinVarData, pathScores);
    }

    public static PathogenicityData empty() {
        return EMPTY_DATA;
    }

    private PathogenicityData(ClinVarData clinVarData, Collection<PathogenicityScore> pathScores) {
        Objects.requireNonNull(clinVarData);
        Objects.requireNonNull(pathScores);
        this.clinVarData = clinVarData;
        pathogenicityScores = new EnumMap<>(PathogenicitySource.class);
        for (PathogenicityScore pathScore : pathScores) {
            if (pathScore != null) {
                pathogenicityScores.put(pathScore.getSource(), pathScore);
            }
        }
    }

    /**
     * Returns a {@link ClinVarData} object. It is highly likely that this field will contain a {@code ClinVarData.empty()}
     * object. For this reason the companion method {@code hasClinVarData()} can be used to check whether there is any real
     * data. Alternatively {@code clinVarData.isEmpty()} can be called on the object returned from this method.
     *
     * @return a {@code ClinVarData} object
     * @since 10.1.0
     */
    public ClinVarData getClinVarData() {
        return clinVarData;
    }

    /**
     * Method used to check whether there is any real ClinVar data associated with this object.
     *
     * @return true if there is any associated ClinVar data, otherwise returns false.
     * @since 10.1.0
     */
    public boolean hasClinVarData() {
        return !clinVarData.isEmpty();
    }

    public List<PathogenicityScore> getPredictedPathogenicityScores() {
        return new ArrayList<>(pathogenicityScores.values());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return this.equals(EMPTY_DATA);
    }

    @JsonIgnore
    public boolean hasPredictedScore() {
        return !pathogenicityScores.isEmpty() || !clinVarData.isEmpty();
    }

    public boolean hasPredictedScore(PathogenicitySource pathogenicitySource) {
        return pathogenicityScores.containsKey(pathogenicitySource);
    }

    /**
     * Returns the PathogenicityScore from the requested source, or null if not present.
     *
     * @param pathogenicitySource
     * @return
     */
    public PathogenicityScore getPredictedScore(PathogenicitySource pathogenicitySource) {
        return pathogenicityScores.get(pathogenicitySource);
    }

    /**
     * @return The most pathogenic score or null if there are no predicted scores
     */
    public PathogenicityScore getMostPathogenicScore() {
        // Add filter step using PathogenicityScore::isPredictedPathogenic?
        return pathogenicityScores.values().stream().max(Comparator.reverseOrder()).orElse(null);
    }


    /**
     * @return the predicted pathogenicity score for this data set. The score is ranked from 0 (non-pathogenic) to 1 (highly pathogenic)
     */
    public float getScore() {
        switch (clinVarData.getPrimaryInterpretation()) {
            case PATHOGENIC:
            case PATHOGENIC_OR_LIKELY_PATHOGENIC:
            case LIKELY_PATHOGENIC:
                return 1f;
            default:
                return getPredictedPathScore();
        }
    }

    private float getPredictedPathScore() {
        if (pathogenicityScores.isEmpty()) {
            return VariantEffectPathogenicityScore.NON_PATHOGENIC_SCORE;
        }

        // Once upon a time we used score-specific cutoffs. These are present in the score classes:
        // Mutation Taster: >0.95 assumed pathogenic, prediction categories not shown
        // Polyphen2 (HVAR): "D" (> 0.956,probably damaging), "P": [0.447-0.955],  possibly damaging, and "B", <0.447, benign.
        // SIFT: "D"<0.05, damaging and "T">0.05, tolerated

        PathogenicityScore mostPathogenicPredictedScore = getMostPathogenicScore();
        //Thanks to SIFT being about tolerance rather than pathogenicity, the score is inverted
        if (mostPathogenicPredictedScore.getClass() == SiftScore.class) {
            return 1 - mostPathogenicPredictedScore.getScore();
        }
        return mostPathogenicPredictedScore.getScore();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathogenicityData that = (PathogenicityData) o;
        return Objects.equals(clinVarData, that.clinVarData) &&
                Objects.equals(pathogenicityScores, that.pathogenicityScores);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clinVarData, pathogenicityScores);
    }

    @Override
    public String toString() {
        return "PathogenicityData{" +
                "clinVarData=" + clinVarData +
                ", pathogenicityScores=" + pathogenicityScores +
                '}';
    }
}
