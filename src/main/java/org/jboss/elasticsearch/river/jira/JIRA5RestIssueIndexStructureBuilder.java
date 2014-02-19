/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.elasticsearch.river.jira;

import static org.elasticsearch.client.Requests.deleteRequest;
import static org.elasticsearch.client.Requests.indexRequest;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.jboss.elasticsearch.tools.content.StructuredContentPreprocessor;

/**
 * JIRA 5 REST API implementation of component responsible to transform issue data obtained from JIRA instance call to
 * the document stored in ElasticSearch index. Intended to cooperate with {@link JIRA5RestClient}.
 * <p>
 * Testing URLs:<br>
 * <code>https://issues.jboss.org/rest/api/2/search?jql=project=ORG</code>
 * <code>https://issues.jboss.org/rest/api/2/search?jql=project=ORG&fields=</code>
 * <code>https://issues.jboss.org/rest/api/2/search?jql=project=ORG&fields=&expand=</code>
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
public class JIRA5RestIssueIndexStructureBuilder implements IJIRAIssueIndexStructureBuilder {

	private static final ESLogger logger = Loggers.getLogger(JIRA5RestIssueIndexStructureBuilder.class);

	/**
	 * JIRA REST response field constant - issue key
	 */
	public static final String JF_KEY = "key";
	/**
	 * JIRA REST response field constant - issue or comment id
	 */
	public static final String JF_ID = "id";
	/**
	 * JIRA REST response field constant - updated date field
	 */
	public static final String JF_UPDATED = "fields.updated";
	/**
	 * JIRA REST response field constant - field where structure of comments is stored
	 */
	public static final String JF_COMMENT = "fields.comment";
	/**
	 * JIRA REST response field constant - field where list of comments is stored
	 */
	public static final String JF_COMMENTS = JF_COMMENT + ".comments";

	/**
	 * JIRA REST response field constant - field where structure of changelogs is stored
	 */
	public static final String JF_CHANGELOG = "changelog";
	/**
	 * JIRA REST response field constant - field where list of changelog items is stored
	 */
	public static final String JF_CHANGELOG_ARRAY = JF_CHANGELOG + ".histories";

	/**
	 * Name of River to be stored in document to mark indexing source
	 */
	protected String riverName;

	/**
	 * Name of ElasticSearch index used to store issues
	 */
	protected String indexName;

	/**
	 * Name of ElasticSearch type used to store issues into index
	 */
	protected String issueTypeName;

	protected static final String CONFIG_FIELDS = "fields";
	protected static final String CONFIG_FIELDS_JIRAFIELD = "jira_field";
	protected static final String CONFIG_FIELDS_VALUEFILTER = "value_filter";
	protected static final String CONFIG_FILTERS = "value_filters";
	protected static final String CONFIG_JIRAFIELD_ISSUEDOCUMENTID = "jira_field_issue_document_id";
	protected static final String CONFIG_FIELDRIVERNAME = "field_river_name";
	protected static final String CONFIG_FIELDPROJECTKEY = "field_project_key";
	protected static final String CONFIG_FIELDISSUEKEY = "field_issue_key";
	protected static final String CONFIG_FIELDJIRAURL = "field_jira_url";
	protected static final String CONFIG_COMMENTMODE = "comment_mode";
	protected static final String CONFIG_FIELDCOMMENTS = "field_comments";
	protected static final String CONFIG_COMMENTTYPE = "comment_type";
	protected static final String CONFIG_COMMENTFILEDS = "comment_fields";

	protected static final String CONFIG_CHANGELOGMODE = "changelog_mode";
	protected static final String CONFIG_FIELDCHANGELOGS = "field_changelogs";
	protected static final String CONFIG_CHANGELOGTYPE = "changelog_type";
	protected static final String CONFIG_CHANGELOGFILEDS = "changelog_fields";

	/**
	 * Field in jira data to get indexed document id from for issue. If empty or do not provide value then issue key is
	 * used as document id.
	 */
	protected String jiraFieldForIssueDocumentId = null;

	/**
	 * Fields configuration structure. Key is name of field in search index. Value is map of configurations for given
	 * index field containing <code>CONFIG_FIELDS_xx</code> constants as keys.
	 */
	protected Map<String, Map<String, String>> fieldsConfig;

	/**
	 * Value filters configuration structure. Key is name of filter. Value is map of filter configurations to be used in
	 * {@link Utils#remapDataInMap(Map, Map)}.
	 */
	protected Map<String, Map<String, String>> filtersConfig;

	/**
	 * Name of field in search index where river name is stored.
	 */
	protected String indexFieldForRiverName = null;

	/**
	 * Name of field in search index where JIRA project key is stored.
	 */
	protected String indexFieldForProjectKey = null;

	/**
	 * Name of field in search index where JIRA issue key is stored.
	 */
	protected String indexFieldForIssueKey = null;

	/**
	 * Name of field in search index where JIRA GUI URL (to issue or comment) is stored.
	 */
	protected String indexFieldForJiraURL = null;

	/**
	 * Set of issue fields requested from JIRA during call
	 */
	protected Set<String> jiraCallFieldSet = new LinkedHashSet<String>();

	/**
	 * Set of issue info expands requested from JIRA during call
	 */
	protected Set<String> jiraCallExpandSet = new LinkedHashSet<String>();

	/**
	 * Base URL used to generate JIRA GUI issue show URL.
	 * 
	 * @see #prepareJIRAGUIUrl(String, String)
	 */
	protected String jiraIssueShowUrlBase;

	/**
	 * Issue comment indexing mode.
	 */
	protected IssueCommentIndexingMode commentIndexingMode;

	/**
	 * Name of field in search index issue document where array of comments is stored in case of
	 * {@link IssueCommentIndexingMode#EMBEDDED}.
	 */
	protected String indexFieldForComments = null;

	/**
	 * Name of ElasticSearch type used to store issues comments into index in case of
	 * {@link IssueCommentIndexingMode#STANDALONE} or {@link IssueCommentIndexingMode#CHILD}.
	 */
	protected String commentTypeName;

	/**
	 * Fields configuration structure for comment document. Key is name of field in search index. Value is map of
	 * configurations for given index field containing <code>CONFIG_FIELDS_xx</code> constants as keys.
	 */
	protected Map<String, Map<String, String>> commentFieldsConfig;

	/**
	 * Issue changelog indexing mode.
	 */
	protected IssueCommentIndexingMode changelogIndexingMode;

	/**
	 * Name of field in search index issue document where array of changelogs is stored in case of
	 * {@link IssueCommentIndexingMode#EMBEDDED}.
	 */
	protected String indexFieldForChangelogs = null;

	/**
	 * Name of ElasticSearch type used to store issues changelogs into index in case of
	 * {@link IssueCommentIndexingMode#STANDALONE} or {@link IssueCommentIndexingMode#CHILD}.
	 */
	protected String changelogTypeName;

	/**
	 * Fields configuration structure for changelog document. Key is name of field in search index. Value is map of
	 * configurations for given index field containing <code>CONFIG_FIELDS_xx</code> constants as keys.
	 */
	protected Map<String, Map<String, String>> changelogFieldsConfig;

	/**
	 * List of data preprocessors used inside {@link #indexIssue(BulkRequestBuilder, String, Map)}.
	 */
	protected List<StructuredContentPreprocessor> issueDataPreprocessors = null;

	/**
	 * Constructor for unit tests. Nothing is filled inside.
	 */
	protected JIRA5RestIssueIndexStructureBuilder() {
		super();
	}

	/**
	 * Constructor.
	 * 
	 * @param riverName name of ElasticSearch River instance this indexer is running inside to be stored in search index
	 *          to identify indexed documents source.
	 * @param indexName name of ElasticSearch index used to store issues
	 * @param issueTypeName name of ElasticSearch type used to store issues into index
	 * @param settings map to load other structure builder settings from
	 * @throws SettingsException
	 */
	@SuppressWarnings("unchecked")
	public JIRA5RestIssueIndexStructureBuilder(String riverName, String indexName, String issueTypeName,
			String jiraUrlBase, Map<String, Object> settings) throws SettingsException {
		super();
		this.riverName = riverName;
		this.indexName = indexName;
		this.issueTypeName = issueTypeName;

		constructJIRAIssueShowUrlBase(jiraUrlBase);
		if (settings != null) {
			jiraFieldForIssueDocumentId = Utils.trimToNull(XContentMapValues.nodeStringValue(
					settings.get(CONFIG_JIRAFIELD_ISSUEDOCUMENTID), null));
			indexFieldForRiverName = XContentMapValues.nodeStringValue(settings.get(CONFIG_FIELDRIVERNAME), null);
			indexFieldForProjectKey = XContentMapValues.nodeStringValue(settings.get(CONFIG_FIELDPROJECTKEY), null);
			indexFieldForIssueKey = XContentMapValues.nodeStringValue(settings.get(CONFIG_FIELDISSUEKEY), null);
			indexFieldForJiraURL = XContentMapValues.nodeStringValue(settings.get(CONFIG_FIELDJIRAURL), null);
			filtersConfig = (Map<String, Map<String, String>>) settings.get(CONFIG_FILTERS);
			fieldsConfig = (Map<String, Map<String, String>>) settings.get(CONFIG_FIELDS);

			commentIndexingMode = IssueCommentIndexingMode.parseConfiguration(
					XContentMapValues.nodeStringValue(settings.get(CONFIG_COMMENTMODE), null), IssueCommentIndexingMode.EMBEDDED);
			indexFieldForComments = XContentMapValues.nodeStringValue(settings.get(CONFIG_FIELDCOMMENTS), null);
			commentTypeName = XContentMapValues.nodeStringValue(settings.get(CONFIG_COMMENTTYPE), null);
			commentFieldsConfig = (Map<String, Map<String, String>>) settings.get(CONFIG_COMMENTFILEDS);

			changelogIndexingMode = IssueCommentIndexingMode.parseConfiguration(
					XContentMapValues.nodeStringValue(settings.get(CONFIG_CHANGELOGMODE), null), IssueCommentIndexingMode.NONE);
			indexFieldForChangelogs = XContentMapValues.nodeStringValue(settings.get(CONFIG_FIELDCHANGELOGS), null);
			changelogTypeName = XContentMapValues.nodeStringValue(settings.get(CONFIG_CHANGELOGTYPE), null);
			changelogFieldsConfig = (Map<String, Map<String, String>>) settings.get(CONFIG_CHANGELOGFILEDS);
		}
		loadDefaultsIfNecessary();
		validateConfiguration();
		prepareJiraCallFieldSet();
	}

	private void loadDefaultsIfNecessary() {
		Map<String, Object> settingsDefault = loadDefaultSettingsMapFromFile();

		filtersConfig = loadDefaultMapIfNecessary(filtersConfig, CONFIG_FILTERS, settingsDefault);
		fieldsConfig = loadDefaultMapIfNecessary(fieldsConfig, CONFIG_FIELDS, settingsDefault);
		indexFieldForRiverName = loadDefaultStringIfNecessary(indexFieldForRiverName, CONFIG_FIELDRIVERNAME,
				settingsDefault);
		indexFieldForProjectKey = loadDefaultStringIfNecessary(indexFieldForProjectKey, CONFIG_FIELDPROJECTKEY,
				settingsDefault);
		indexFieldForIssueKey = loadDefaultStringIfNecessary(indexFieldForIssueKey, CONFIG_FIELDISSUEKEY, settingsDefault);
		indexFieldForJiraURL = loadDefaultStringIfNecessary(indexFieldForJiraURL, CONFIG_FIELDJIRAURL, settingsDefault);

		if (commentIndexingMode == null) {
			commentIndexingMode = IssueCommentIndexingMode.parseConfiguration(
					XContentMapValues.nodeStringValue(settingsDefault.get(CONFIG_COMMENTMODE), null),
					IssueCommentIndexingMode.EMBEDDED);
		}
		indexFieldForComments = loadDefaultStringIfNecessary(indexFieldForComments, CONFIG_FIELDCOMMENTS, settingsDefault);
		commentTypeName = loadDefaultStringIfNecessary(commentTypeName, CONFIG_COMMENTTYPE, settingsDefault);
		commentFieldsConfig = loadDefaultMapIfNecessary(commentFieldsConfig, CONFIG_COMMENTFILEDS, settingsDefault);

		if (changelogIndexingMode == null) {
			changelogIndexingMode = IssueCommentIndexingMode.parseConfiguration(
					XContentMapValues.nodeStringValue(settingsDefault.get(CONFIG_CHANGELOGMODE), null),
					IssueCommentIndexingMode.NONE);
		}
		indexFieldForChangelogs = loadDefaultStringIfNecessary(indexFieldForChangelogs, CONFIG_FIELDCHANGELOGS,
				settingsDefault);
		changelogTypeName = loadDefaultStringIfNecessary(changelogTypeName, CONFIG_CHANGELOGTYPE, settingsDefault);
		changelogFieldsConfig = loadDefaultMapIfNecessary(changelogFieldsConfig, CONFIG_CHANGELOGFILEDS, settingsDefault);
	}

	private void validateConfiguration() {

		validateConfigurationObject(filtersConfig, "index/value_filters");
		validateConfigurationObject(fieldsConfig, "index/fields");
		validateConfigurationString(indexFieldForRiverName, "index/field_river_name");
		validateConfigurationString(indexFieldForProjectKey, "index/field_project_key");
		validateConfigurationString(indexFieldForIssueKey, "index/field_issue_key");
		validateConfigurationString(indexFieldForJiraURL, "index/field_jira_url");
		validateConfigurationObject(commentIndexingMode, "index/comment_mode");
		validateConfigurationObject(commentFieldsConfig, "index/comment_fields");
		validateConfigurationString(commentTypeName, "index/comment_type");
		validateConfigurationString(indexFieldForComments, "index/field_comments");

		validateConfigurationObject(changelogIndexingMode, "index/changelog_mode");
		validateConfigurationObject(changelogFieldsConfig, "index/changelog_fields");
		validateConfigurationString(changelogTypeName, "index/changelog_type");
		validateConfigurationString(indexFieldForChangelogs, "index/field_changelogs");

		validateConfigurationFieldsStructure(fieldsConfig, "index/fields");
		validateConfigurationFieldsStructure(commentFieldsConfig, "index/comment_fields");

	}

	protected void prepareJiraCallFieldSet() {
		jiraCallFieldSet.clear();
		jiraCallExpandSet.clear();
		// fields always necessary to get from jira
		jiraCallFieldSet.add(getJiraCallFieldName(JF_UPDATED));
		// other fields from configuration
		for (Map<String, String> fc : fieldsConfig.values()) {
			String jf = getJiraCallFieldName(fc.get(CONFIG_FIELDS_JIRAFIELD));
			if (jf != null) {
				jiraCallFieldSet.add(jf);
			}
		}
		if (commentIndexingMode != null && commentIndexingMode != IssueCommentIndexingMode.NONE) {
			jiraCallFieldSet.add(getJiraCallFieldName(JF_COMMENT));
		}
		if (changelogIndexingMode != null && changelogIndexingMode != IssueCommentIndexingMode.NONE) {
			jiraCallExpandSet.add("changelog");
		}
	}

	@Override
	public void addIssueDataPreprocessor(StructuredContentPreprocessor preprocessor) {
		if (preprocessor == null)
			return;

		if (issueDataPreprocessors == null)
			issueDataPreprocessors = new ArrayList<StructuredContentPreprocessor>();

		issueDataPreprocessors.add(preprocessor);

	}

	@Override
	public String getIssuesSearchIndexName(String jiraProjectKey) {
		return indexName;
	}

	@Override
	public String getRequiredJIRACallIssueFields() {
		return Utils.createCsvString(jiraCallFieldSet);
	}

	@Override
	public String getRequiredJIRACallIssueExpands() {
		return Utils.createCsvString(jiraCallExpandSet);
	}

	@Override
	public void indexIssue(BulkRequestBuilder esBulk, String jiraProjectKey, Map<String, Object> issue) throws Exception {

		issue = preprocessIssueData(jiraProjectKey, issue);
		esBulk.add(indexRequest(indexName).type(issueTypeName).id(prepareIssueDocumentId(issue))
				.source(prepareIssueIndexedDocument(jiraProjectKey, issue)));

		if (commentIndexingMode.isExtraDocumentIndexed()) {
			List<Map<String, Object>> comments = extractIssueComments(issue);
			if (comments != null && !comments.isEmpty()) {
				String issueKey = extractIssueKey(issue);
				for (Map<String, Object> comment : comments) {
					String commentId = extractCommentId(comment);
					IndexRequest irq = indexRequest(indexName).type(commentTypeName).id(commentId)
							.source(prepareCommentIndexedDocument(jiraProjectKey, issueKey, comment));
					if (commentIndexingMode == IssueCommentIndexingMode.CHILD) {
						irq.parent(issueKey);
					}
					esBulk.add(irq);
				}
			}
		}

		if (changelogIndexingMode.isExtraDocumentIndexed()) {
			List<Map<String, Object>> changelogs = extractIssueChangelogs(issue);
			if (changelogs != null && !changelogs.isEmpty()) {
				String issueKey = extractIssueKey(issue);
				for (Map<String, Object> changelog : changelogs) {
					String commentId = extractChangelogId(changelog);
					IndexRequest irq = indexRequest(indexName).type(changelogTypeName).id(commentId)
							.source(prepareChangelogIndexedDocument(jiraProjectKey, issueKey, changelog));
					if (changelogIndexingMode == IssueCommentIndexingMode.CHILD) {
						irq.parent(issueKey);
					}
					esBulk.add(irq);
				}
			}
		}

	}

	protected String prepareIssueDocumentId(Map<String, Object> issue) {
		String documentId = null;
		if (jiraFieldForIssueDocumentId != null) {
			documentId = Utils.trimToNull(XContentMapValues.nodeStringValue(
					XContentMapValues.extractValue(jiraFieldForIssueDocumentId, issue), null));
		}
		if (documentId == null)
			documentId = extractIssueKey(issue);
		return documentId;
	}

	@Override
	public String extractIssueKey(Map<String, Object> issue) {
		return XContentMapValues.nodeStringValue(issue.get(JF_KEY), null);
	}

	@Override
	public Date extractIssueUpdated(Map<String, Object> issue) {
		return DateTimeUtils.parseISODateTime(XContentMapValues.nodeStringValue(
				XContentMapValues.extractValue(JF_UPDATED, issue), null));
	}

	public String extractCommentId(Map<String, Object> comment) {
		return XContentMapValues.nodeStringValue(comment.get(JF_ID), null);
	}

	public String extractChangelogId(Map<String, Object> changelog) {
		return XContentMapValues.nodeStringValue(changelog.get(JF_ID), null);
	}

	/**
	 * Preprocess issue data over all configured preprocessors.
	 * 
	 * @param jiraProjectKey issue is for
	 * @param issue data to preprocess
	 * @return preprocessed issue data
	 */
	protected Map<String, Object> preprocessIssueData(String jiraProjectKey, Map<String, Object> issue) {
		if (issueDataPreprocessors != null) {
			for (StructuredContentPreprocessor prepr : issueDataPreprocessors) {
				issue = prepr.preprocessData(issue);
			}
		}
		return issue;
	}

	@Override
	public void buildSearchForIndexedDocumentsNotUpdatedAfter(SearchRequestBuilder srb, String jiraProjectKey, Date date) {
		FilterBuilder filterTime = FilterBuilders.rangeFilter("_timestamp").lt(date);
		FilterBuilder filterProject = FilterBuilders.termFilter(indexFieldForProjectKey, jiraProjectKey);
		FilterBuilder filterSource = FilterBuilders.termFilter(indexFieldForRiverName, riverName);
		FilterBuilder filter = FilterBuilders.boolFilter().must(filterTime).must(filterProject).must(filterSource);
		srb.setQuery(QueryBuilders.matchAllQuery()).addField("_id").setPostFilter(filter);
		Set<String> st = new LinkedHashSet<String>();
		st.add(issueTypeName);
		if (commentIndexingMode.isExtraDocumentIndexed())
			st.add(commentTypeName);
		if (changelogIndexingMode.isExtraDocumentIndexed())
			st.add(changelogTypeName);
		srb.setTypes(st.toArray(new String[st.size()]));
	}

	@Override
	public boolean deleteIssueDocument(BulkRequestBuilder esBulk, SearchHit documentToDelete) throws Exception {
		esBulk.add(deleteRequest(indexName).type(documentToDelete.getType()).id(documentToDelete.getId()));
		return issueTypeName.equals(documentToDelete.getType());
	}

	/**
	 * Convert JIRA returned issue REST data into JSON document to be stored in search index.
	 * 
	 * @param jiraProjectKey key of jira project document is for.
	 * @param issue issue data from JIRA REST call
	 * @return JSON builder with issue document for index
	 * @throws Exception
	 */
	protected XContentBuilder prepareIssueIndexedDocument(String jiraProjectKey, Map<String, Object> issue)
			throws Exception {
		String issueKey = extractIssueKey(issue);

		XContentBuilder out = jsonBuilder().startObject();
		addValueToTheIndexField(out, indexFieldForRiverName, riverName);
		addValueToTheIndexField(out, indexFieldForProjectKey, jiraProjectKey);
		addValueToTheIndexField(out, indexFieldForIssueKey, issueKey);
		addValueToTheIndexField(out, indexFieldForJiraURL, prepareJIRAGUIUrl(issueKey, null));

		for (String indexFieldName : fieldsConfig.keySet()) {
			Map<String, String> fieldConfig = fieldsConfig.get(indexFieldName);
			addValueToTheIndex(out, indexFieldName, fieldConfig.get(CONFIG_FIELDS_JIRAFIELD), issue,
					fieldConfig.get(CONFIG_FIELDS_VALUEFILTER));
		}

		if (commentIndexingMode == IssueCommentIndexingMode.EMBEDDED) {
			List<Map<String, Object>> comments = extractIssueComments(issue);
			if (comments != null && !comments.isEmpty()) {
				out.startArray(indexFieldForComments);
				for (Map<String, Object> comment : comments) {
					out.startObject();
					addCommonFieldsToCommentIndexedDocument(out, issueKey, comment);
					out.endObject();
				}
				out.endArray();
			}
		}
		if (changelogIndexingMode == IssueCommentIndexingMode.EMBEDDED) {
			List<Map<String, Object>> changelogs = extractIssueChangelogs(issue);
			if (changelogs != null && !changelogs.isEmpty()) {
				out.startArray(indexFieldForChangelogs);
				for (Map<String, Object> changelog : changelogs) {
					out.startObject();
					addCommonFieldsToChangelogIndexedDocument(out, issueKey, changelog);
					out.endObject();
				}
				out.endArray();
			}
		}
		return out.endObject();
	}

	/**
	 * Convert JIRA returned REST data into JSON document to be stored in search index for comments in child and
	 * standalone mode.
	 * 
	 * @param projectKey key of jira project document is for.
	 * @param issueKey this comment is for
	 * @param comment data from JIRA REST call
	 * @return JSON builder with comment document for index
	 * @throws Exception
	 */
	protected XContentBuilder prepareCommentIndexedDocument(String projectKey, String issueKey,
			Map<String, Object> comment) throws Exception {
		XContentBuilder out = jsonBuilder().startObject();
		addValueToTheIndexField(out, indexFieldForRiverName, riverName);
		addValueToTheIndexField(out, indexFieldForProjectKey, projectKey);
		addValueToTheIndexField(out, indexFieldForIssueKey, issueKey);
		addCommonFieldsToCommentIndexedDocument(out, issueKey, comment);
		return out.endObject();
	}

	private void addCommonFieldsToCommentIndexedDocument(XContentBuilder out, String issueKey, Map<String, Object> comment)
			throws Exception {
		addValueToTheIndexField(out, indexFieldForJiraURL, prepareJIRAGUIUrl(issueKey, extractCommentId(comment)));
		for (String indexFieldName : commentFieldsConfig.keySet()) {
			Map<String, String> commentFieldConfig = commentFieldsConfig.get(indexFieldName);
			addValueToTheIndex(out, indexFieldName, commentFieldConfig.get(CONFIG_FIELDS_JIRAFIELD), comment,
					commentFieldConfig.get(CONFIG_FIELDS_VALUEFILTER));
		}
	}

	/**
	 * Get comments for issue from JIRA JSON data.
	 * 
	 * @param issue Map of Maps issue data structure loaded from JIRA.
	 * @return list of comments if available in issu data
	 */
	@SuppressWarnings("unchecked")
	protected List<Map<String, Object>> extractIssueComments(Map<String, Object> issue) {
		List<Map<String, Object>> comments = (List<Map<String, Object>>) XContentMapValues.extractValue(JF_COMMENTS, issue);
		return comments;
	}

	/**
	 * Convert JIRA returned REST data into JSON document to be stored in search index for changelogs in child and
	 * standalone mode.
	 * 
	 * @param projectKey key of jira project document is for.
	 * @param issueKey this changelog is for
	 * @param changelog data from JIRA REST call
	 * @return JSON builder with changelog document for index
	 * @throws Exception
	 */
	protected XContentBuilder prepareChangelogIndexedDocument(String projectKey, String issueKey,
			Map<String, Object> changelog) throws Exception {
		XContentBuilder out = jsonBuilder().startObject();
		addValueToTheIndexField(out, indexFieldForRiverName, riverName);
		addValueToTheIndexField(out, indexFieldForProjectKey, projectKey);
		addValueToTheIndexField(out, indexFieldForIssueKey, issueKey);
		addCommonFieldsToChangelogIndexedDocument(out, issueKey, changelog);
		return out.endObject();
	}

	private void addCommonFieldsToChangelogIndexedDocument(XContentBuilder out, String issueKey,
			Map<String, Object> changelog) throws Exception {
		addValueToTheIndexField(out, indexFieldForJiraURL, prepareJIRAGUIUrl(issueKey, null));
		for (String indexFieldName : changelogFieldsConfig.keySet()) {
			Map<String, String> changelogFieldConfig = changelogFieldsConfig.get(indexFieldName);
			addValueToTheIndex(out, indexFieldName, changelogFieldConfig.get(CONFIG_FIELDS_JIRAFIELD), changelog,
					changelogFieldConfig.get(CONFIG_FIELDS_VALUEFILTER));
		}
	}

	/**
	 * Get changelogs for issue from JIRA JSON data.
	 * 
	 * @param issue Map of Maps issue data structure loaded from JIRA.
	 * @return list of changelogs if available in issue data
	 */
	@SuppressWarnings("unchecked")
	protected List<Map<String, Object>> extractIssueChangelogs(Map<String, Object> issue) {
		List<Map<String, Object>> changelogs = (List<Map<String, Object>>) XContentMapValues.extractValue(
				JF_CHANGELOG_ARRAY, issue);
		return changelogs;
	}

	/**
	 * Prepare URL of issue or comment in JIRA GUI.
	 * 
	 * @param issueKey mandatory key of JIRA issue.
	 * @param commentId id of comment, may be null
	 * @return URL to show issue or comment in JIRA GUI
	 */
	public String prepareJIRAGUIUrl(String issueKey, String commentId) {
		if (commentId == null) {
			return jiraIssueShowUrlBase + issueKey;
		} else {
			return jiraIssueShowUrlBase + issueKey + "?focusedCommentId=" + commentId
					+ "&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-" + commentId;
		}
	}

	/**
	 * Get defined value from values structure and add it into index document. Calls
	 * {@link #addValueToTheIndex(XContentBuilder, String, String, Map, Map)} and receive filter from
	 * {@link #filtersConfig} based on passed <code>valueFieldFilterName</code>)
	 * 
	 * @param out content builder to add indexed value field into
	 * @param indexField name of field for index
	 * @param valuePath path to get value from <code>values</code> structure. Dot notation for nested values can be used
	 *          here (see {@link XContentMapValues#extractValue(String, Map)}).
	 * @param values structure to get value from. Can be <code>null</code> - nothing added in this case, but not
	 *          exception.
	 * @param valueFieldFilterName name of filter definition to get it from {@link #filtersConfig}
	 * @throws Exception
	 */
	protected void addValueToTheIndex(XContentBuilder out, String indexField, String valuePath,
			Map<String, Object> values, String valueFieldFilterName) throws Exception {
		Map<String, String> filter = null;
		if (!Utils.isEmpty(valueFieldFilterName)) {
			filter = filtersConfig.get(valueFieldFilterName);
		}
		addValueToTheIndex(out, indexField, valuePath, values, filter);
	}

	/**
	 * Get defined value from values structure and add it into index document.
	 * 
	 * @param out content builder to add indexed value field into
	 * @param indexField name of field for index
	 * @param valuePath path to get value from <code>values</code> structure. Dot notation for nested values can be used
	 *          here (see {@link XContentMapValues#extractValue(String, Map)}).
	 * @param values structure to get value from. Can be <code>null</code> - nothing added in this case, but not
	 *          exception.
	 * @param valueFieldFilter if value is JSON Object (java Map here) or List of JSON Objects, then fields in this
	 *          objects are filtered to leave only fields named here and remap them - see
	 *          {@link Utils#remapDataInMap(Map, Map)}. No filtering performed if this is <code>null</code>.
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	protected void addValueToTheIndex(XContentBuilder out, String indexField, String valuePath,
			Map<String, Object> values, Map<String, String> valueFieldFilter) throws Exception {
		if (values == null) {
			return;
		}
		Object v = null;
		if (valuePath.contains(".")) {
			v = XContentMapValues.extractValue(valuePath, values);
		} else {
			v = values.get(valuePath);
		}
		if (v != null && valueFieldFilter != null && !valueFieldFilter.isEmpty()) {
			if (v instanceof Map) {
				Utils.remapDataInMap((Map<String, Object>) v, valueFieldFilter);
			} else if (v instanceof List) {
				for (Object o : (List<?>) v) {
					if (o instanceof Map) {
						Utils.remapDataInMap((Map<String, Object>) o, valueFieldFilter);
					} else {
						logger.warn("Filter defined for field which is not filterable - jira array field '{}' with value: {}",
								valuePath, v);
					}
				}
			} else {
				logger.warn("Filter defined for field which is not filterable - jira field '{}' with value: {}", valuePath, v);
			}
		}
		addValueToTheIndexField(out, indexField, v);
	}

	/**
	 * Add value into field in index document. Do not add it if value is <code>null</code>!
	 * 
	 * @param out builder to add field into.
	 * @param indexField real name of field used in index.
	 * @param value to be added to the index field. Can be <code>null</code>, nothing added in this case
	 * @throws Exception
	 * 
	 * @see {@link XContentBuilder#field(String, Object)}.
	 */
	protected void addValueToTheIndexField(XContentBuilder out, String indexField, Object value) throws Exception {
		if (value != null)
			out.field(indexField, value);
	}

	/**
	 * Get name of JIRA field used in REST call from full jira field name.
	 * 
	 * @param fullJiraFieldName
	 * @return call field name or null
	 * @see #getRequiredJIRACallIssueFields()
	 */
	protected static String getJiraCallFieldName(String fullJiraFieldName) {
		if (Utils.isEmpty(fullJiraFieldName)) {
			return null;
		}
		fullJiraFieldName = fullJiraFieldName.trim();
		if (fullJiraFieldName.startsWith("fields.")) {
			String jcrf = fullJiraFieldName.substring("fields.".length());
			if (Utils.isEmpty(jcrf)) {
				logger.warn("Bad format of jira field name '{}', nothing will be in search index", fullJiraFieldName);
				return null;
			}
			if (jcrf.contains(".")) {
				jcrf = jcrf.substring(0, jcrf.indexOf("."));
			}
			if (Utils.isEmpty(jcrf)) {
				logger.warn("Bad format of jira field name '{}', nothing will be in search index", fullJiraFieldName);
				return null;
			}
			return jcrf.trim();
		} else {
			return null;
		}
	}

	/**
	 * Construct value for {@link #jiraIssueShowUrlBase}.
	 * 
	 * @param jiraUrlBase base URL of jira instance
	 */
	protected void constructJIRAIssueShowUrlBase(String jiraUrlBase) {
		jiraIssueShowUrlBase = jiraUrlBase;
		if (!jiraIssueShowUrlBase.endsWith("/")) {
			jiraIssueShowUrlBase += "/";
		}
		jiraIssueShowUrlBase += "browse/";
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> loadDefaultSettingsMapFromFile() throws SettingsException {
		Map<String, Object> json = Utils.loadJSONFromJarPackagedFile("/templates/jira_river_configuration_default.json");
		return (Map<String, Object>) json.get("index");
	}

	private String loadDefaultStringIfNecessary(String valueToCheck, String valueConfigKey,
			Map<String, Object> settingsDefault) {
		if (Utils.isEmpty(valueToCheck)) {
			return XContentMapValues.nodeStringValue(settingsDefault.get(valueConfigKey), null);
		} else {
			return valueToCheck.trim();
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Map<String, String>> loadDefaultMapIfNecessary(Map<String, Map<String, String>> valueToCheck,
			String valueConfigKey, Map<String, Object> settingsDefault) {
		if (valueToCheck == null || valueToCheck.isEmpty()) {
			valueToCheck = (Map<String, Map<String, String>>) settingsDefault.get(valueConfigKey);
		}
		return valueToCheck;
	}

	private void validateConfigurationFieldsStructure(Map<String, Map<String, String>> value, String configFieldName) {
		for (String idxFieldName : value.keySet()) {
			if (Utils.isEmpty(idxFieldName)) {
				throw new SettingsException("Empty key found in '" + configFieldName + "' map.");
			}
			Map<String, String> fc = value.get(idxFieldName);
			if (Utils.isEmpty(fc.get(CONFIG_FIELDS_JIRAFIELD))) {
				throw new SettingsException("'jira_field' is not defined in '" + configFieldName + "/" + idxFieldName + "'");
			}
			String fil = fc.get(CONFIG_FIELDS_VALUEFILTER);
			if (fil != null && !filtersConfig.containsKey(fil)) {
				throw new SettingsException("Filter definition not found for filter name '" + fil + "' defined in '"
						+ configFieldName + "/" + idxFieldName + "/value_filter'");
			}
		}
	}

	private void validateConfigurationString(String value, String configFieldName) throws SettingsException {
		if (Utils.isEmpty(value)) {
			throw new SettingsException("No default '" + configFieldName + "' configuration found!");
		}
	}

	private void validateConfigurationObject(Object value, String configFieldName) throws SettingsException {
		if (value == null) {
			throw new SettingsException("No default '" + configFieldName + "' configuration found!");
		}
	}

}
