/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.kenyaemr.reporting.indicator.evaluator;

import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.SessionFactory;
import org.openmrs.*;
import org.openmrs.annotation.Handler;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemr.MetadataConstants;
import org.openmrs.module.kenyaemr.reporting.indicator.HivCareVisitsIndicator;
import org.openmrs.module.kenyaemr.reporting.library.cohort.CommonCohortLibrary;
import org.openmrs.module.reporting.cohort.EvaluatedCohort;
import org.openmrs.module.reporting.cohort.definition.service.CohortDefinitionService;
import org.openmrs.module.reporting.common.DateUtil;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.indicator.Indicator;
import org.openmrs.module.reporting.indicator.SimpleIndicatorResult;

import org.openmrs.module.reporting.indicator.evaluator.IndicatorEvaluator;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.openmrs.module.kenyaemr.reporting.EmrReportingUtils.map;

/**
 * Evaluator for HIV care visit indicators
 */
@Handler(supports = HivCareVisitsIndicator.class)
public class HivCareVisitsIndicatorEvaluator implements IndicatorEvaluator {

	protected static final Log log = LogFactory.getLog(HivCareVisitsIndicatorEvaluator.class);

	@Autowired
	SessionFactory sessionFactory;

	@Autowired
	CommonCohortLibrary cohortLibrary;

	@Override
	public SimpleIndicatorResult evaluate(Indicator indicator, EvaluationContext context) throws EvaluationException {
		HivCareVisitsIndicator visitIndicator = (HivCareVisitsIndicator) indicator;

		List<Form> hivCareForms = Arrays.asList(
			Context.getFormService().getFormByUuid(MetadataConstants.CLINICAL_ENCOUNTER_HIV_ADDENDUM_FORM_UUID),
			Context.getFormService().getFormByUuid(MetadataConstants.MOH_257_VISIT_SUMMARY_FORM_UUID)
		);

		Date fromDate = visitIndicator.getStartDate();
		Date toDate = DateUtil.getEndOfDayIfTimeExcluded(visitIndicator.getEndDate());

		log.debug("Evaluating HIV care visits from " + fromDate + " to " + toDate);

		List<Encounter> hivCareEncounters = Context.getEncounterService().getEncounters(null, null, fromDate, toDate, hivCareForms, null, null, null, null, false);
		List<Encounter> filtered = new ArrayList<Encounter>();

		if (HivCareVisitsIndicator.Filter.FEMALES_18_AND_OVER.equals(visitIndicator.getFilter())) {
			EvaluatedCohort females18AndOver = Context.getService(CohortDefinitionService.class).evaluate(
					map(cohortLibrary.femalesAgedAtLeast18(), "effectiveDate", "${endDate}"), context
			);

			for (Encounter enc : hivCareEncounters) {
				if (females18AndOver.contains(enc.getPatient().getPatientId())) {
					filtered.add(enc);
				}
			}
		}
		else if (HivCareVisitsIndicator.Filter.SCHEDULED.equals(visitIndicator.getFilter())) {
			for (Encounter enc : hivCareEncounters) {
				if (wasScheduledVisit(enc)) {
					filtered.add(enc);
				}
			}
		}
		else if (HivCareVisitsIndicator.Filter.UNSCHEDULED.equals(visitIndicator.getFilter())) {
			for (Encounter enc : hivCareEncounters) {
				if (!wasScheduledVisit(enc)) {
					filtered.add(enc);
				}
			}
		}
		else {
			filtered = hivCareEncounters;
		}

		SimpleIndicatorResult result = new SimpleIndicatorResult();
		result.setIndicator(indicator);
		result.setContext(context);
		result.setNumeratorResult(filtered.size());

		return result;
	}

	/**
	 * Determines whether the given encounter was part of a scheduled visit
	 * @param encounter the encounter
	 * @return true if was part of scheduled visit
	 */
	private boolean wasScheduledVisit(Encounter encounter) {
		// Firstly look for a scheduled visit obs which has value = true
		Concept scheduledVisit = Context.getConceptService().getConceptByUuid(MetadataConstants.SCHEDULED_VISIT_CONCEPT_UUID);
		for (Obs obs : encounter.getAllObs()) {
			if (obs.getConcept().equals(scheduledVisit) && obs.getValueAsBoolean()) {
				return true;
			}
		}

		Date visitDate = (encounter.getVisit() != null) ? encounter.getVisit().getStartDatetime() : encounter.getEncounterDatetime();
		Concept returnVisitDate = Context.getConceptService().getConceptByUuid(MetadataConstants.RETURN_VISIT_DATE_CONCEPT_UUID);
		List<Obs> returnVisitObss = Context.getObsService().getObservationsByPersonAndConcept(encounter.getPatient(), returnVisitDate);

		for (Obs returnVisitObs : returnVisitObss) {
			if (DateUtils.isSameDay(returnVisitObs.getValueDate(), visitDate)) {
				return true;
			}
		}

		return false;
	}
}