/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.speechtotext.impl.engine;

import org.opencastproject.speechtotext.api.SpeechToTextEngine;
import org.opencastproject.speechtotext.api.SpeechToTextEngineException;
import org.opencastproject.util.IoSupport;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** whispercpp implementation of the Speech-to-text engine interface. */
@Component(
    property = {
        "service.description=WhisperC++ implementation of the SpeechToTextEngine interface",
        "enginetype=whispercpp"
    }
)

public class WhisperCppEngine implements SpeechToTextEngine {

  private static final Logger logger = LoggerFactory.getLogger(WhisperCppEngine.class);

  /** Name of the engine. */
  private static final String engineName = "whispercpp";

  /** Config key for setting the path to whispercpp. */
  private static final String WHISPERCPP_EXECUTABLE_PATH_CONFIG_KEY = "whispercpp.root.path";

  /** Default path to whispercpp. */
  public static final String WHISPERCPP_EXECUTABLE_DEFAULT_PATH = "whispercpp";

  /** Currently used path of the whispercpp installation. */
  private String whispercppExecutable = WHISPERCPP_EXECUTABLE_DEFAULT_PATH;

  /** Config key for setting whispercpp model */
  private static final String WHISPERCPP_MODEL_CONFIG_KEY = "whispercpp.model";

  /** Default whispercpp model */
  public static final String WHISPERCPP_MODEL_DEFAULT = "base";

  /** Currently used whispercpp model */
  private String whispercppModel = WHISPERCPP_MODEL_DEFAULT;

  /** Config key to set default language */
  private static final String WHISPERCPP_DEFAULT_LANGUAGE_KEY = "whispercpp.default.language";

  /** Default Language */
  public static final String WHISPERCPP_DEFAULT_LANGUAGE = "en";

  /** Currently used default language for Vosk */
  private String whispercppLanguage = WHISPERCPP_DEFAULT_LANGUAGE;


  @Override
  public String getEngineName() {
    return engineName;
  }

  @Activate
  @Modified
  public void activate(ComponentContext cc) {
    logger.debug("Activated/Modified WhisperC++ engine service class");
    whispercppExecutable = StringUtils.defaultIfBlank(
        (String) cc.getProperties().get(WHISPERCPP_EXECUTABLE_PATH_CONFIG_KEY), WHISPERCPP_EXECUTABLE_DEFAULT_PATH);
    logger.debug("Set WhisperC++ path to {}", whispercppExecutable);

    whispercppModel = StringUtils.defaultIfBlank(
        (String) cc.getProperties().get(WHISPERCPP_MODEL_CONFIG_KEY), WHISPERCPP_MODEL_DEFAULT);
    logger.debug("WhisperC++ Language model set to {}", whispercppModel);

    logger.debug("Finished activating/updating speech-to-text service");
  }

  /**
   * {@inheritDoc}
   *
   * @see org.opencastproject.speechtotext.api.SpeechToTextEngine#generateSubtitlesFile(File, File, String)
   */

  //TODO: Add method for language detection
  //TODO: Add optional language translation to english
  @Override
  public File generateSubtitlesFile(File mediaFile, File preparedOutputFile, String language)
          throws SpeechToTextEngineException {

    if (language.isBlank()) {
      logger.debug("Language field empty, using {} as default language", whispercppLanguage);
      language = whispercppLanguage;
    }

    final List<String> command = Arrays.asList(
        whispercppExecutable,
        "-ovtt",
        "-bs 5",
        "--model", "/usr/share/ggml/ggml-" + whispercppModel + ".bin",
        "--output-file", preparedOutputFile.getAbsolutePath(),
        "-l", language,
        "-f", mediaFile.getAbsolutePath());
    logger.info("Executing WhisperC++'s transcription command: {}", command);

    Process process = null;
    try {
      ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.redirectErrorStream(true);
      process = processBuilder.start();

      // wait until the task is finished
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        var error = "";
        try (var errorStream = process.getInputStream()) {
          error = "\n Output:\n" + IOUtils.toString(errorStream, StandardCharsets.UTF_8);
        }
        throw new SpeechToTextEngineException(
            String.format("WhisperC++ exited abnormally with status %d (command: %s)%s", exitCode, command, error));
      }

      // Renaming output whispercpp filename to the expected output filename
      String mediaFileName = mediaFile.getName();
      String mediaFileNameWithoutExtension = mediaFileName.lastIndexOf('.') != -1
          ? mediaFileName.substring(0, mediaFileName.lastIndexOf('.')) : mediaFileName;
      File whispercppVTT = new File((preparedOutputFile.getParent() + "/" + mediaFileNameWithoutExtension));
      whispercppVTT.renameTo(preparedOutputFile);

      if (!preparedOutputFile.isFile()) {
        throw new SpeechToTextEngineException("WhisperC++ produced no output");
      }
      logger.info("Subtitles file generated successfully: {}", preparedOutputFile);
    } catch (Exception e) {
      logger.debug("Transcription failed closing WhisperC++ transcription process for: {}", mediaFile);
      throw new SpeechToTextEngineException(e);
    } finally {
      IoSupport.closeQuietly(process);
    }

    return preparedOutputFile; // now containing subtitles data
  }
}
