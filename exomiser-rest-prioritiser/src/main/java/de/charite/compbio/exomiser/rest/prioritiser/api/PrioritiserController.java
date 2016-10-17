/*
 * The Exomiser - A tool to annotate and prioritize variants
 *
 * Copyright (C) 2012 - 2016  Charite Universitätsmedizin Berlin and Genome Research Ltd.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.charite.compbio.exomiser.rest.prioritiser.api;

import de.charite.compbio.exomiser.core.factories.GeneFactory;
import de.charite.compbio.exomiser.core.model.Gene;
import de.charite.compbio.exomiser.core.prioritisers.*;
import de.charite.compbio.jannovar.data.JannovarData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
@RestController
public class PrioritiserController {

    private static final Logger logger = LoggerFactory.getLogger(PrioritiserController.class);

    private final PriorityFactory priorityFactory;
    private final Map<String, String> geneIdentifiers;

    @Autowired
    public PrioritiserController(PriorityFactory priorityFactory, JannovarData jannovarData) {
        this.priorityFactory = priorityFactory;
        this.geneIdentifiers = GeneFactory.createKnownGeneIdentifiers(jannovarData);
        logger.info("Created GeneIdentifier cache with {} entries", geneIdentifiers.size());
    }

    @GetMapping(value = "info")
    public String info() {
        return "This service will return a collection of prioritiser results for any given set of:" +
                "\n\t - HPO identifiers e.g. HPO:00001" +
                "\n\t - Entrez gene identifiers e.g. 23364" +
                "\n\t - Specified prioritiser e.g. hiphive along with any prioritiser specific commands e.g. human,mouse,fish,ppi";
    }

    @GetMapping(value = "", produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public PrioritiserResultSet prioritise(@RequestParam(value = "phenotypes") List<String> phenotypes,
                                           @RequestParam(value = "genes", required = false, defaultValue = "") List<Integer> genesIds,
                                           @RequestParam(value = "prioritiser") String prioritiserName,
                                           @RequestParam(value = "prioritiser-params", required = false, defaultValue = "") String prioritiserParams,
                                           @RequestParam(value = "limit", required = false, defaultValue = "0") Integer limit
    ) {

        logger.info("phenotypes: {}({}) genes: {} prioritiser: {} prioritiser-params: {}", phenotypes, phenotypes.size(), genesIds, prioritiserName, prioritiserParams);
        PriorityType priorityType = parsePrioritserType(prioritiserName.trim());
        Prioritiser prioritiser = setUpPrioritiser(phenotypes, prioritiserParams, priorityType);

        //this is a slow step - GeneIdentifiers should be used instead of genes. GeneIdentifiers can be cached.
        Instant start = Instant.now();
        List<Gene> genes = parseGeneIdentifiers(genesIds);

        prioritiser.prioritizeGenes(genes);
        //in an ideal world this would return Stream<PriorityResult> results = prioritiser.prioritise(hpoIds, geneIds)
        //and the next section would be mostly superfluous
        List<PriorityResult> results = getPrioritiserResults(limit, priorityType, genes);

        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("phenotypes", phenotypes.toString());
        params.put("genes", genesIds.toString());
        params.put("prioritiser", prioritiserName);
        params.put("prioritiser-params", prioritiserParams);
        params.put("limit", limit.toString());

        return new PrioritiserResultSet(params, duration.toMillis(), results);
    }

    Prioritiser setUpPrioritiser(List<String> phenotypes, String prioritiserParams, PriorityType priorityType) {
        List<String> uniquePhenotypes = phenotypes.stream().distinct().collect(toList());
        PrioritiserSettings prioritiserSettings = new PrioritiserSettingsImpl.PrioritiserSettingsBuilder().hpoIdList(uniquePhenotypes).exomiser2Params(prioritiserParams).build();
        return priorityFactory.makePrioritiser(priorityType, prioritiserSettings);
    }

    private PriorityType parsePrioritserType(String prioritiserName) {
        switch(prioritiserName) {
            case "phenix":
                return PriorityType.PHENIX_PRIORITY;
            case "phive":
                return PriorityType.PHIVE_PRIORITY;
            case "hiphive":
            default:
                return PriorityType.HIPHIVE_PRIORITY;
        }
    }

    private List<Gene> parseGeneIdentifiers(List<Integer> genesIds) {
        if (genesIds.isEmpty()) {
            logger.info("Gene identifiers not specified - will compare against all known genes.");
            //If not specified, we'll assume they want to use the whole genome. Should save people a lot of typing.
            //n.b. Gene is mutable so these can't be cached and returned.
            return geneIdentifiers.entrySet().parallelStream()
                    //geneId and geneSymbol are the same in cases where
                    .map(entry -> {
                        String geneId = entry.getKey();
                        String geneSymbol = entry.getValue();
                        if (geneId.equals(geneSymbol)) {
                            return new Gene(geneSymbol, -1);
                        }
                        //we're assuming Entrez ids here.
                        return new Gene(geneSymbol, Integer.parseInt(geneId));
                    })
                    .collect(toList());
        }
        //this is a hack - really the Prioritiser should only work on GeneIds, but currently this isn't possible as OmimPrioritiser uses some properties of Gene
        return genesIds.stream()
                .map(id -> new Gene(geneIdentifiers.getOrDefault(Integer.toString(id), "GENE:" + id), id))
                .collect(toList());
    }

    List<PriorityResult> getPrioritiserResults(int limit, PriorityType priorityType, List<Gene> genes) {
        Stream<PriorityResult> sortedPriorityResultsStream = genes.stream()
                .map(gene -> gene.getPriorityResult(priorityType))
                .sorted(Comparator.naturalOrder());

        if (limit == 0) {
            return sortedPriorityResultsStream.collect(toList());
        }
        return sortedPriorityResultsStream.limit(limit).collect(toList());
    }
}
