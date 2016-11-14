package com.psddev.cms.hunspell;

import com.atlascopco.hunspell.Hunspell;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.psddev.cms.db.ToolUser;
import com.psddev.cms.nlp.SpellChecker;
import com.psddev.dari.db.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Spell checker implementation using
 * <a href="http://hunspell.sourceforge.net/">Hunspell</a>.
 *
 * <p>The dictionary files should be in the classpath with their names
 * starting with {@code HunspellDictionary} and ending with
 * {@link #AFFIX_FILE_SUFFIX} or {@link #DICTIONARY_FILE_SUFFIX}.</p>
 *
 * <p>For example, if the locale is {@code ko-KR}, the affix file should be
 * named {@code HunspellDictionary_ko_KR.aff}, and the dictionary file should
 * be named {@code HunspellDictionary_ko_KR.dic}.</p>
 */
public class HunspellSpellChecker implements SpellChecker {

    Logger LOGGER = LoggerFactory.getLogger(HunspellSpellChecker.class);

    /**
     * Affix file suffix/extension.
     *
     * @see <a href="http://sourceforge.net/projects/hunspell/files/Hunspell/Documentation/">Hunspell Manual</a>
     */
    public static final String AFFIX_FILE_SUFFIX = ".aff";

    /**
     * Dictionary file suffix/extension.
     *
     * @see <a href="http://sourceforge.net/projects/hunspell/files/Hunspell/Documentation/">Hunspell Manual</a>
     */
    public static final String DICTIONARY_FILE_SUFFIX = ".dic";

    private boolean areUserDictionaryWordsAdded = false;

    private final LoadingCache<String, Optional<Hunspell>> hunspells = CacheBuilder
            .newBuilder()
            .removalListener(new RemovalListener<String, Optional<Hunspell>>() {

                @Override
                @ParametersAreNonnullByDefault
                public void onRemoval(RemovalNotification<String, Optional<Hunspell>> removalNotification) {
                    Optional<Hunspell> hunspellOptional = removalNotification.getValue();

                    if (hunspellOptional != null) {
                        hunspellOptional.ifPresent(Hunspell::close);
                    }
                }
            })
            .build(new CacheLoader<String, Optional<Hunspell>>() {

                @Override
                @ParametersAreNonnullByDefault
                public Optional<Hunspell> load(String name) throws IOException {
                    List<String> arr = Arrays.asList(name.split("&"));

                    name = arr.get(0);
                    String userID = arr.get(1).replace('&', '_');

                    try (InputStream affixInput = getClass().getResourceAsStream("/" + name + AFFIX_FILE_SUFFIX)) {
                        if (affixInput != null) {
                            try (InputStream dictionaryInput = getClass().getResourceAsStream("/" + name + DICTIONARY_FILE_SUFFIX)) {
                                if (dictionaryInput != null) {
                                    String prefixPath = name + userID;
                                    String tmpdir = System.getProperty("java.io.tmpdir");
                                    Path affixPath = Paths.get(tmpdir, prefixPath + AFFIX_FILE_SUFFIX);
                                    Path dictionaryPath = Paths.get(tmpdir, prefixPath + DICTIONARY_FILE_SUFFIX + userID);

                                    Files.copy(affixInput, affixPath, StandardCopyOption.REPLACE_EXISTING);
                                    Files.copy(dictionaryInput, dictionaryPath, StandardCopyOption.REPLACE_EXISTING);

                                    return Optional.of(new Hunspell(dictionaryPath.toString(), affixPath.toString()));
                                }
                            }
                        }
                    }
                    return Optional.empty();
                }
            });

    private Hunspell findHunspell(ToolUser user, Locale locale) {

        return SpellChecker.createDictionaryNames("HunspellDictionary", locale)
                .stream()
                .map(l -> {
                    String name = l.concat("&").concat(user.getId().toString());
                    return hunspells.getUnchecked(name).orElse(null);
                })
                .filter(h -> h != null)
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean isSupported(ToolUser user, Locale locale) {
        Preconditions.checkNotNull(locale);

        return findHunspell(user, locale) != null;
    }

    @Override
    public boolean isPreferred(Locale locale) {
        Preconditions.checkNotNull(locale);

        return false;
    }

    @Override
    public List<String> suggest(ToolUser user, Locale locale, String word) {
        Preconditions.checkNotNull(locale);
        Preconditions.checkNotNull(word);

        Hunspell hunspell = findHunspell(user, locale);

        if (hunspell == null) {
            throw new UnsupportedOperationException();

        } else if (hunspell.spell(word)) {
            return null;

        } else {

            if (!areUserDictionaryWordsAdded) {
                UserPersonalDictionary userDictionary = Query.from(UserPersonalDictionary.class).where("userId = ?", user.getId()).first();
                if (userDictionary == null) {
                    userDictionary = new UserPersonalDictionary();
                    userDictionary.setUserId(user.getId());
                    userDictionary.save();
                }

                for (String userWord : userDictionary.getWords()) {
                    hunspell.add(userWord);
                }
                areUserDictionaryWordsAdded = true;
            }

            return hunspell.suggest(word);
        }
    }

    public void addToDictionary(ToolUser user, Locale locale, String word) {
        Preconditions.checkNotNull(locale);
        Preconditions.checkNotNull(word);

        Hunspell hunspell = findHunspell(user, locale);

        if (hunspell == null) {
            throw new UnsupportedOperationException();

        } else if (hunspell.spell(word)) {
            return;
        } else {

            UserPersonalDictionary userDictionary = Query.from(UserPersonalDictionary.class).where("userId = ?", user.getId()).first();
            if (userDictionary == null) {
                userDictionary = new UserPersonalDictionary();
                userDictionary.setUserId(user.getId());
            }
            userDictionary.add(word);
            userDictionary.save();

            hunspell.add(word);
        }
    }
}
