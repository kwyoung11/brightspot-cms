package com.psddev.cms.tool.page;

import com.google.common.base.Preconditions;
import com.psddev.cms.db.ToolUser;
import com.psddev.cms.tool.AuthenticationFilter;
import com.psddev.cms.tool.PageServlet;
import com.psddev.cms.nlp.SpellChecker;
import com.psddev.cms.tool.ToolPageContext;
import com.psddev.dari.util.CompactMap;
import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.PageContextFilter;
import com.psddev.dari.util.RoutingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RoutingFilter.Path(application = "cms", value = "spellCheck")
public class SpellCheck extends PageServlet {

    private static final String ADD_WORD_PARAM = "addWord";
    private static final String SUGGEST_WORD_PARAM = "suggestWords";
    private static final String CONTENT_TYPE = "application/javascript";

    @Override
    protected String getPermissionId() {
        return null;
    }

    @Override
    protected void doService(ToolPageContext page) throws IOException, ServletException {
        Map<String, Object> response = new CompactMap<>();

        boolean isActionSuggestWord = page.paramNamesList().contains(SUGGEST_WORD_PARAM);
        boolean isActionAddWord = page.paramNamesList().contains(ADD_WORD_PARAM);

        if (isActionSuggestWord) {
            suggestWords(page, response);
            return;
        } else if (isActionAddWord) {
            addWord(page, response);
            return;
        }
    }

    private void suggestWords(ToolPageContext page, Map<String, Object> response) throws IOException {
        try {
            ToolUser user = page.getUser();
            String userId = user.getId().toString();
            Locale languageTag = Locale.forLanguageTag(page.param(String.class, "locale"));
            Locale locale = new Locale(languageTag.getLanguage(), languageTag.getCountry(), userId);

            SpellChecker spellChecker = SpellChecker.getInstance(user, locale);

            if (spellChecker == null) {
                response.put("status", "unsupported");

            } else {
                response.put("status", "supported");

                List<String> words = page.params(String.class, "word");

                if (!ObjectUtils.isBlank(words)) {
                    response.put("results", words
                            .stream()
                            .map(word -> spellChecker.suggest(user, locale, word))
                            .collect(Collectors.toList()));
                }
            }

        } catch (Exception error) {
            response.put("status", "error");
            response.put("message", error.getMessage());
        }

        page.getResponse().setContentType(CONTENT_TYPE);
        page.write(ObjectUtils.toJson(response));
    }

    private void addWord(ToolPageContext page, Map<String, Object> response) throws IOException {
        try {
            ToolUser user = page.getUser();
            String userId = user.getId().toString();
            Locale IetfBcp47FLocale = Locale.forLanguageTag(page.param(String.class, "locale"));
            Locale locale = new Locale(IetfBcp47FLocale.getLanguage(), IetfBcp47FLocale.getCountry(), userId);
            SpellChecker spellChecker = SpellChecker.getInstance(user, locale);

            if (spellChecker == null) {
                response.put("status", "unsupported");

            } else {
                response.put("status", "supported");
                String word = page.param(String.class, "word");
                spellChecker.addToDictionary(user, locale, word);
            }

        } catch (Exception error) {
            response.put("status", "error");
            response.put("message", error.getMessage());
        }

        page.getResponse().setContentType(CONTENT_TYPE);
        page.write(ObjectUtils.toJson(response));
    }
}
