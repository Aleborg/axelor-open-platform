/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2021 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.meta.loader;

import com.axelor.common.FileUtils;
import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.axelor.meta.MetaScanner;
import com.axelor.meta.db.MetaTranslation;
import com.axelor.meta.db.repo.MetaTranslationRepository;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.inject.persist.Transactional;
import com.opencsv.CSVParser;
import com.opencsv.CSVReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

public class I18nLoader extends AbstractParallelLoader {

  @Inject private MetaTranslationRepository translations;

  /** Separate custom csv files from the rest. */
  private <T> List<List<T>> separateFiles(List<T> files) {

    final List<T> main = new ArrayList<>();
    final List<T> custom = new ArrayList<>();
    final Pattern pattern = Pattern.compile(".*(?:custom_)(\\w+)\\.csv$");

    for (final T file : files) {
      final String name = file.toString();
      if (pattern.matcher(name).matches()) {
        custom.add(file);
      } else {
        main.add(file);
      }
    }

    return ImmutableList.of(main, custom);
  }

  @Override
  protected void doLoad(URL file, Module module, boolean update) {
    log.debug("Load translation: {}", file.getFile());

    try (InputStream is = file.openStream()) {
      process(is, file.getFile());
    } catch (IOException e) {
      log.error("Unable to import file: {}", file.getFile());
    }
  }

  @Override
  protected List<List<URL>> findFileLists(Module module) {
    final List<URL> files = MetaScanner.findAll(module.getName(), "i18n", "(.*?)\\.csv");
    return separateFiles(files);
  }

  @Transactional
  public void load(String importPath) {
    // Import by module resolver order
    for (final Module module : ModuleManager.getAll()) {
      if (Strings.isNullOrEmpty(importPath)) {
        this.doLoad(module, false);
      } else {
        this.loadModule(module, importPath);
      }
    }
  }

  private void loadModule(Module module, String importPath) {

    File moduleDir = FileUtils.getFile(importPath, module.getName());
    if (!moduleDir.exists() || !moduleDir.isDirectory() || moduleDir.listFiles() == null) {
      return;
    }

    log.debug("Load {} translations", module.getName());

    final List<List<File>> fileLists = separateFiles(Arrays.asList(moduleDir.listFiles()));

    fileLists.forEach(
        files ->
            files.forEach(
                file -> {
                  try (InputStream is = new FileInputStream(file)) {
                    process(is, file.getPath());
                  } catch (IOException e) {
                    log.error("Unable to import file: {}", file.getName());
                  }
                }));
  }

  private void process(InputStream stream, String fileName) throws IOException {
    // Get language name from the file name
    String language = "";
    Pattern pattern = Pattern.compile(".*(?:messages_|custom_)([a-zA-Z_]+)\\.csv$");
    Matcher matcher = pattern.matcher(fileName);

    if (!matcher.matches()) {
      return;
    }

    language = matcher.group(1);

    try (Reader reader = new InputStreamReader(stream, Charsets.UTF_8);
        CSVReader csvReader =
            new CSVReader(
                reader, CSVParser.DEFAULT_SEPARATOR, CSVParser.DEFAULT_QUOTE_CHARACTER, '\0')) {

      String[] fields = csvReader.readNext();
      String[] values = null;

      int counter = 0;

      while ((values = csvReader.readNext()) != null) {
        if (isEmpty(values)) {
          continue;
        }
        Map<String, String> map = toMap(fields, values);

        String key = map.get("key");
        String message = map.get("message");

        if (StringUtils.isBlank(key)) {
          continue;
        }

        MetaTranslation entity = translations.findByKey(key, language);
        if (entity == null) {
          entity = new MetaTranslation();
          entity.setKey(key);
          entity.setLanguage(language);
          entity.setMessage(message);
        } else if (!StringUtils.isBlank(message)) {
          entity.setMessage(message);
        }

        translations.save(entity);

        if (counter++ % 20 == 0) {
          JPA.em().flush();
          JPA.em().clear();
        }
      }
    }
  }

  private Map<String, String> toMap(String[] fields, String[] values) {
    Map<String, String> map = Maps.newHashMap();
    for (int i = 0; i < fields.length; i++) {
      map.put(fields[i], values[i]);
    }
    return map;
  }

  private boolean isEmpty(String[] line) {
    if (line == null || line.length == 0) return true;
    if (line.length == 1 && (line[0] == null || "".equals(line[0].trim()))) return true;
    return false;
  }
}
