package com.psddev.cms.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.joda.time.DateTime;

import com.psddev.cms.db.Directory;
import com.psddev.cms.db.Renderer;
import com.psddev.cms.db.Site;
import com.psddev.cms.db.ToolUi;
import com.psddev.dari.db.Database;
import com.psddev.dari.db.Metric;
import com.psddev.dari.db.MetricInterval;
import com.psddev.dari.db.ObjectField;
import com.psddev.dari.db.ObjectType;
import com.psddev.dari.db.Predicate;
import com.psddev.dari.db.Query;
import com.psddev.dari.db.Recordable;
import com.psddev.dari.db.State;
import com.psddev.dari.util.CompactMap;
import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.PaginatedResult;
import com.psddev.dari.util.StorageItem;

public class ListSearchResultView extends AbstractSearchResultView {

    private static final String ATTRIBUTE_PREFIX = SearchResultRenderer.class.getName() + ".";
    private static final String PREVIOUS_DATE_ATTRIBUTE = ATTRIBUTE_PREFIX + "previousDate";
    private static final String MAX_SUM_ATTRIBUTE = ATTRIBUTE_PREFIX + ".maximumSum";

    protected ObjectField sortField;
    protected boolean showSiteLabel;
    protected boolean showTypeLabel;
    protected PaginatedResult<?> result;

    @Override
    public String getIconName() {
        return "list-ul";
    }

    @Override
    public String getDisplayName() {
        return "List";
    }

    @Override
    protected void doWriteHtml() throws IOException {
        ObjectType selectedType = search.getSelectedType();

        sortField = updateSort();
        showSiteLabel = Query.from(CmsTool.class).first().isDisplaySiteInSearchResult() &&
                page.getSite() == null &&
                Query.from(Site.class).hasMoreThan(0);

        if (selectedType != null) {
            showTypeLabel = selectedType.as(ToolUi.class).findDisplayTypes().size() != 1;

            if (ObjectType.getInstance(ObjectType.class).equals(selectedType)) {
                List<ObjectType> types = new ArrayList<ObjectType>();
                Predicate predicate = search.toQuery(page.getSite()).getPredicate();

                for (ObjectType t : Database.Static.getDefault().getEnvironment().getTypes()) {
                    if (t.is(predicate)) {
                        types.add(t);
                    }
                }

                result = new PaginatedResult<ObjectType>(search.getOffset(), search.getLimit(), types);
            }

        } else {
            showTypeLabel = search.findValidTypes().size() != 1;
        }

        if (result == null) {
            result = search.toQuery(page.getSite()).select(search.getOffset(), search.getLimit());
        }

        writeSortsHtml();
        writePaginationHtml(result);

        page.writeStart("div", "class", "searchResultList");
            if (result.hasPages()) {
                writeItemsHtml(result.getItems());

            } else {
                writeEmptyHtml();
            }
        page.writeEnd();
    }

    protected void writeItemsHtml(Collection<?> items) throws IOException {
        List<Object> tableItems = new ArrayList<Object>(items);
        Map<Object, StorageItem> previews = new CompactMap<Object, StorageItem>();

        ITEM: for (ListIterator<Object> i = tableItems.listIterator(); i.hasNext();) {
            Object item = i.next();

            for (Tool tool : Query.from(Tool.class).selectAll()) {
                if (!tool.isDisplaySearchResultItem(search, item)) {
                    continue ITEM;
                }
            }

            State itemState = State.getInstance(item);
            StorageItem preview = itemState.getPreview();

            if (preview != null) {
                String contentType = preview.getContentType();

                if (contentType != null && contentType.startsWith("image/")) {
                    i.remove();
                    previews.put(item, preview);
                }
            }
        }

        if (!previews.isEmpty()) {
            page.writeStart("div", "class", "searchResultImages");
                for (Map.Entry<Object, StorageItem> entry : previews.entrySet()) {
                    writeImageHtml(entry.getKey(), entry.getValue());
                }
            page.writeEnd();
        }

        if (!tableItems.isEmpty()) {
            page.writeStart("table", "class", "searchResultTable links table-striped pageThumbnails");
                page.writeStart("tbody");
                    for (Object item : tableItems) {
                        writeTableRowHtml(item);
                    }
                page.writeEnd();
            page.writeEnd();
        }
    }

    protected void writeImageHtml(Object item, StorageItem image) throws IOException {
        itemWriter.writeBeforeHtml(page, item);

        page.writeStart("figure");
            page.writeElement("img",
                    "src", page.getPreviewThumbnailUrl(item),
                    "alt", (showSiteLabel ? page.getObjectLabel(State.getInstance(item).as(Site.ObjectModification.class).getOwner()) + ": " : "") +
                            (showTypeLabel ? page.getTypeLabel(item) + ": " : "") +
                            page.getObjectLabel(item));

            page.writeStart("figcaption");
                if (showSiteLabel) {
                    page.writeObjectLabel(State.getInstance(item).as(Site.ObjectModification.class).getOwner());
                    page.writeHtml(": ");
                }

                if (showTypeLabel) {
                    page.writeTypeLabel(item);
                    page.writeHtml(": ");
                }

                page.writeObjectLabel(item);
            page.writeEnd();
        page.writeEnd();

        itemWriter.writeAfterHtml(page, item);
    }

    protected void writeTableRowHtml(Object item) throws IOException {
        HttpServletRequest request = page.getRequest();
        State itemState = State.getInstance(item);
        String permalink = itemState.as(Directory.ObjectModification.class).getPermalink();
        Integer embedWidth = null;

        if (ObjectUtils.isBlank(permalink)) {
            ObjectType type = itemState.getType();

            if (type != null) {
                Renderer.TypeModification rendererData = type.as(Renderer.TypeModification.class);
                int previewWidth = rendererData.getEmbedPreviewWidth();

                if (previewWidth > 0 &&
                        !ObjectUtils.isBlank(rendererData.getEmbedPath())) {

                    permalink = "/_preview?_embed=true&_cms.db.previewId=" + itemState.getId();
                    embedWidth = 320;
                }
            }
        }

        page.writeStart("tr",
                "data-preview-url", permalink,
                "data-preview-embed-width", embedWidth,
                "class", State.getInstance(item).getId().equals(page.param(UUID.class, "id")) ? "selected" : null);

            if (sortField != null &&
                    ObjectField.DATE_TYPE.equals(sortField.getInternalType())) {

                DateTime dateTime = page.toUserDateTime(itemState.get(sortField.getInternalName()));

                if (dateTime == null) {
                    page.writeStart("td", "colspan", 2);
                        page.writeHtml("N/A");
                    page.writeEnd();

                } else {
                    String date = page.formatUserDate(dateTime);

                    page.writeStart("td", "class", "date");
                        if (!ObjectUtils.equals(date, request.getAttribute(PREVIOUS_DATE_ATTRIBUTE))) {
                            request.setAttribute(PREVIOUS_DATE_ATTRIBUTE, date);
                            page.writeHtml(date);
                        }
                    page.writeEnd();

                    page.writeStart("td", "class", "time");
                        page.writeHtml(page.formatUserTime(dateTime));
                    page.writeEnd();
                }
            }

            if (showSiteLabel) {
                page.writeStart("td");
                    page.writeObjectLabel(itemState.as(Site.ObjectModification.class).getOwner());
                page.writeEnd();
            }

            if (showTypeLabel) {
                page.writeStart("td");
                    page.writeTypeLabel(item);
                page.writeEnd();
            }

            page.writeStart("td", "data-preview-anchor", "");
                itemWriter.writeBeforeHtml(page, item);
                page.writeObjectLabel(item);
                itemWriter.writeAfterHtml(page, item);
            page.writeEnd();

            if (sortField != null &&
                    !ObjectField.DATE_TYPE.equals(sortField.getInternalType())) {

                String sortFieldName = sortField.getInternalName();
                Object value = itemState.get(sortFieldName);

                page.writeStart("td");
                    if (value instanceof Metric) {
                        page.writeStart("span", "style", page.cssString("white-space", "nowrap"));
                            Double maxSum = (Double) request.getAttribute(MAX_SUM_ATTRIBUTE);

                            if (maxSum == null) {
                                Object maxObject = search.toQuery(page.getSite()).sortDescending(sortFieldName).first();
                                maxSum = maxObject != null ?
                                        ((Metric) State.getInstance(maxObject).get(sortFieldName)).getSum() :
                                        1.0;

                                request.setAttribute(MAX_SUM_ATTRIBUTE, maxSum);
                            }

                            Metric valueMetric = (Metric) value;
                            Map<DateTime, Double> sumEntries = valueMetric.groupSumByDate(
                                    new MetricInterval.Daily(),
                                    new DateTime().dayOfMonth().roundFloorCopy().minusDays(7),
                                    null);

                            double sum = valueMetric.getSum();
                            long sumLong = (long) sum;

                            if (sumLong == sum) {
                                page.writeHtml(String.format("%,2d ", sumLong));

                            } else {
                                page.writeHtml(String.format("%,2.2f ", sum));
                            }

                            if (!sumEntries.isEmpty()) {
                                long minMillis = Long.MAX_VALUE;
                                long maxMillis = Long.MIN_VALUE;

                                for (Map.Entry<DateTime, Double> sumEntry : sumEntries.entrySet()) {
                                    long sumMillis = sumEntry.getKey().getMillis();

                                    if (sumMillis < minMillis) {
                                        minMillis = sumMillis;
                                    }

                                    if (sumMillis > maxMillis) {
                                        maxMillis = sumMillis;
                                    }
                                }

                                double cumulativeSum = 0.0;
                                StringBuilder path = new StringBuilder();
                                double xRange = maxMillis - minMillis;
                                int width = 35;
                                int height = 18;

                                for (Map.Entry<DateTime, Double> sumEntry : sumEntries.entrySet()) {
                                    cumulativeSum += sumEntry.getValue();

                                    path.append('L');
                                    path.append((sumEntry.getKey().getMillis() - minMillis) / xRange * width);
                                    path.append(',');
                                    path.append(height - cumulativeSum / maxSum * height);
                                }

                                path.setCharAt(0, 'M');

                                page.writeStart("svg",
                                        "xmlns", "http://www.w3.org/2000/svg",
                                        "width", width,
                                        "height", height,
                                        "style", page.cssString(
                                                "display", "inline-block",
                                                "vertical-align", "middle"));

                                    page.writeStart("path",
                                            "fill", "none",
                                            "stroke", "#444444",
                                            "d", path.toString());
                                    page.writeEnd();
                                page.writeEnd();
                            }
                        page.writeEnd();

                    } else if (value instanceof Recordable) {
                        page.writeHtml(((Recordable) value).getState().getLabel());

                    } else {
                        page.writeHtml(value);
                    }
                page.writeEnd();
            }
        page.writeEnd();
    }
}
