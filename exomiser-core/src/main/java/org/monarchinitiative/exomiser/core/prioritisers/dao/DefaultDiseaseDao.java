/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2021 Queen Mary University of London.
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

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.monarchinitiative.exomiser.core.prioritisers.dao;

import com.google.common.collect.ImmutableSet;
import org.monarchinitiative.exomiser.core.prioritisers.model.Disease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
@Repository
public class DefaultDiseaseDao implements DiseaseDao {

    private final Logger logger = LoggerFactory.getLogger(DefaultDiseaseDao.class);

    private final DataSource dataSource;

    public DefaultDiseaseDao(@Qualifier("phenotypeDataSource") DataSource phenotypeDataSource) {
        this.dataSource = phenotypeDataSource;
    }

    @Cacheable(value = "diseaseHp")
    @Override
    public Set<String> getHpoIdsForDiseaseId(String diseaseId) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT hp_id FROM disease_hp WHERE disease_id = ?")) {
                statement.setString(1, diseaseId);
                ResultSet rs = statement.executeQuery();
                Set<String> diseaseHpoIds = parseHpoIds(rs);
                logger.info("{} HPO ids retrieved for disease {} - {}", diseaseHpoIds.size(), diseaseId, diseaseHpoIds);
                return diseaseHpoIds;

            }
        } catch (SQLException e) {
            logger.error("Unable to retrieve HPO terms for disease {}", diseaseId, e);
        }
        return Collections.emptySet();
    }

    private Set<String> parseHpoIds(ResultSet rs) throws SQLException {
        if (rs.next()) {
            String hpoListString = rs.getString("hp_id");
            String[] hpoArray = hpoListString.split(",");
            return ImmutableSet.copyOf(hpoArray);
        }
        return Collections.emptySet();
    }

    @Cacheable(value = "diseases")
    @Override
    public List<Disease> getDiseaseDataAssociatedWithGeneId(int geneId) {
        String query = "SELECT" +
                " gene_id AS entrez_id" +
                ", symbol AS human_gene_symbol" +
                ", d.disease_id AS disease_id" +
                ", d.diseasename AS disease_name" +
                ", d.type AS disease_type" +
                ", d.inheritance AS inheritance_code" +
                ", hp_id AS pheno_ids " +
                "FROM entrez2sym e, disease_hp dhp, disease d " +
                "WHERE dhp.disease_id = d.disease_id " +
                "AND e.entrezid = d.gene_id " +
                "AND d.type in ('D', 'C', 'S', '?')" +
                "AND d.gene_id = ?";

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setInt(1, geneId);
                ResultSet rs = statement.executeQuery();
                return processDiseaseResults(rs);

            }
        } catch (SQLException e) {
            logger.error("Unable to execute query '{}' for geneId: '{}'", query, geneId, e);
        }
        return Collections.emptyList();
    }

    private List<Disease> processDiseaseResults(ResultSet rs) throws SQLException {
        List<Disease> listBuilder = new ArrayList<>();
        while (rs.next()) {
            List<String> phenotypes = List.of(rs.getString("pheno_ids").split(","));
            Disease disease = Disease.builder()
                    .diseaseId(rs.getString("disease_id"))
                    .diseaseName(rs.getString("disease_name"))
                    .associatedGeneId(rs.getInt("entrez_id"))
                    .associatedGeneSymbol(rs.getString("human_gene_symbol"))
                    .inheritanceModeCode(formatInheritanceCode(rs.getString("inheritance_code")))
                    .diseaseTypeCode(rs.getString("disease_type"))
                    .phenotypeIds(phenotypes)
                    .build();
            listBuilder.add(disease);
        }
        return List.copyOf(listBuilder);
    }

    // work-around for the inheritance code being defined as a char and interpreted as a char2 which will contain whitespace
    // this should be set to varchar(2). Not trimming the inheritanceCode results in all inheritance modes being UNKNOWN.
    private String formatInheritanceCode(String inheritanceCode) {
        return inheritanceCode == null ? "U" : inheritanceCode.trim();
    }
}
