/*
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

import static java.nio.file.StandardOpenOption.CREATE;
import static javax.ws.rs.core.Response.Status.ACCEPTED;
import static javax.ws.rs.core.Response.Status.OK;

import org.opencastproject.speechtotext.api.SpeechToTextEngine;
import org.opencastproject.speechtotext.api.SpeechToTextEngineException;

import com.google.gson.Gson;

import org.apache.commons.io.FilenameUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** WaaS implementation of the Speech-to-text engine interface. */
@Component(
    property = {
        "service.description=WaaS implementation of the SpeechToTextEngine interface",
        "enginetype=waas"
    }
)
public class WaasEngine implements SpeechToTextEngine {

  private static final Logger logger = LoggerFactory.getLogger(WaasEngine.class);

  private static final String engineName = "WaaS";

  private static final String WAAS_HOST = "waas.host";
  private static final String WAAS_RETRY_COUNT = "waas.retry";
  private static final String WAAS_TIMEOUT = "waas.timeout";
  private static final String WAAS_FALLBACK_LANGUAGE = "waas.fallback-language";

  /** Config key for automatic audio encoding */
  private static final String AUTO_ENCODING_CONFIG_KEY = "waas.auto-encode";

  /** Default value for automatic audio encoding */
  private static final Boolean AUTO_ENCODING_DEFAULT = true;

  /** If Opencast should automatically re-encode tracks so that they are compatible with Whisper.cpp */
  private boolean autoEncode = AUTO_ENCODING_DEFAULT;

  /** The key to look for in the service configuration file to override the DEFAULT_FFMPEG_BINARY */
  public static final String FFMPEG_BINARY_CONFIG_KEY = "org.opencastproject.composer.ffmpeg.path";

  /** The default path to the ffmpeg binary */
  public static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";

  /** Path to the executable */
  protected String ffmpegBinary = DEFAULT_FFMPEG_BINARY;

  private String host = "http://localhost:8080";

  private int retryCount = 3;

  private int timeout = 3600;

  private String fallbackLanguage = null;

  private HttpClient client;

  private Gson gson = new Gson();

  private static final Map<String, String> languageMap;

  static {
    languageMap = new HashMap<>();
    for (Locale locale : Locale.getAvailableLocales()) {
      languageMap.put(locale.getISO3Language(), locale.getLanguage());
    }
  }

  @Override
  public String getEngineName() {
    return engineName;
  }

  @Activate
  @Modified
  public void activate(ComponentContext cc) {
    logger.debug("Activated/Modified WaaS engine service class");
    host = (String) cc.getProperties().get(WAAS_HOST);
    retryCount = Integer.parseInt((String) cc.getProperties().get(WAAS_RETRY_COUNT));
    timeout = Integer.parseInt((String)cc.getProperties().get(WAAS_TIMEOUT));
    fallbackLanguage = (String) cc.getProperties().get(WAAS_FALLBACK_LANGUAGE);
    client = HttpClient.newBuilder().build();

    autoEncode = BooleanUtils.toBoolean(Objects.toString(
        cc.getProperties().get(AUTO_ENCODING_CONFIG_KEY),
        AUTO_ENCODING_DEFAULT.toString()));
    logger.debug("Automatically convert input media: {}", autoEncode);

    ffmpegBinary = Objects.toString(cc.getBundleContext().getProperty(FFMPEG_BINARY_CONFIG_KEY), DEFAULT_FFMPEG_BINARY);
    logger.debug("ffmpeg binary set to {}", ffmpegBinary);

    logger.debug("Finished activating/updating speech-to-text service");
  }

  @Deactivate
  public void deactivate() {
    logger.debug("Deactivated WaaS engine service class");
    client = null;
  }

  /**
   * {@inheritDoc}
   *
   * @see SpeechToTextEngine#generateSubtitlesFile(File, File, String, Boolean)
   */
  @Override
  public Result generateSubtitlesFile(File mediaFile, File workingDirectory, String language, Boolean translate)
          throws SpeechToTextEngineException {

    var whisperInput = mediaFile.getAbsolutePath();
    if (autoEncode) {
      whisperInput = FilenameUtils.concat(workingDirectory.getAbsolutePath(), UUID.randomUUID() + ".wav");
      var ffmpegCommand = List.of(
          ffmpegBinary,
          "-i", mediaFile.getAbsolutePath(),
          "-ar", "16000",
          "-ac", "1",
          "-c:a", "pcm_s16le",
          whisperInput);
      try {
        execCommand(ffmpegCommand);
      } catch (IOException e) {
        throw new SpeechToTextEngineException("Failed to convert audio file", e);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    if (!whisperInput.toLowerCase().endsWith(".wav")) {
      throw new SpeechToTextEngineException("WaaS currently doesn't support any media extension other than wav");
    }

    String outputName = FilenameUtils.getBaseName(whisperInput);
    File vtt = new File(workingDirectory, outputName + ".vtt");

    if (language.isEmpty()) {
      language = fallbackLanguage;
    } else if (language.length() == 3) {
      language = languageMap.get(language);
    }
    String subtitleLanguage = language;

    try {
      var multiPartBody = new HTTPRequestMultipartBody.Builder().addPart("languageCode", language)
          .addPart("audioFile", whisperInput, "audio/wav", whisperInput.getName()).build();

      var transcribe = HttpRequest.newBuilder().uri(URI.create(host + "/waas/transcribe"))
          .header("Content-Type",multiPartBody.getContentType())
          .header("Accept", "application/json")
          .header("X-Request-Retry", String.valueOf(retryCount)).timeout(Duration.ofSeconds(30))
          .POST(HttpRequest.BodyPublishers.ofByteArray(multiPartBody.getBody())).build();

      HttpResponse<String> jobResponse = client.send(transcribe,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (jobResponse.statusCode() != ACCEPTED.getStatusCode()) {
        throw new SpeechToTextEngineException("WaaS transcription failed status code " + jobResponse.statusCode());
      }

      TranscriptionJob job = gson.fromJson(jobResponse.body(), TranscriptionJob.class);
      logger.info("Transcription job submitted: {}", job.getId());

      var status = HttpRequest.newBuilder().uri(URI.create(host + "/waas/job/" + job.getId() + "/status"))
          .header("Accept", "application/json").header("X-Request-Retry", String.valueOf(retryCount))
          .timeout(Duration.ofSeconds(30)).GET().build();

      //Super-duper hacky way to wait
      int waiting = 0;
      while (true) {
        HttpResponse<String> statusResponse = client.send(status,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (statusResponse.statusCode() != OK.getStatusCode()) {
          throw new SpeechToTextEngineException("WaaS transcription failed status code " + statusResponse.statusCode());
        }

        TranscriptionState state = gson.fromJson(statusResponse.body(), TranscriptionState.class);
        logger.trace("Transcription job status: {}", state);

        if (state == TranscriptionState.COMPLETED) {
          logger.info("Transcription job completed: {}", job.getId());
          break;
        } else if (state == TranscriptionState.FAILED) {
          throw new SpeechToTextEngineException("WaaS transcription job failed");
        }

        try {
          TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
          throw new SpeechToTextEngineException(e);
        }

        waiting += 10;
        if (waiting % 60 == 0) {
          logger.info("Transcription job still in progress: {}", job.getId());
        }

        if (waiting >= timeout) {
          throw new SpeechToTextEngineException("WaaS transcription job timed out");
        }
      }

      var result = HttpRequest.newBuilder().uri(URI.create(host + "/waas/job/" + job.getId() + "/result"))
          .header("Accept", "application/octet-stream").header("X-Request-Retry", String.valueOf(retryCount))
          .timeout(Duration.ofSeconds(30)).GET().build();

      HttpResponse<String> resultResponse = client.send(result,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (resultResponse.statusCode() != OK.getStatusCode()) {
        throw new SpeechToTextEngineException("WaaS transcription failed status code " + resultResponse.statusCode());
      }
      Files.writeString(vtt.toPath(), resultResponse.body(), StandardCharsets.UTF_8, CREATE);

    } catch (FileNotFoundException e) {
      throw new SpeechToTextEngineException("Audio file not found", e);
    } catch (IOException | InterruptedException e) {
      throw new SpeechToTextEngineException(e);
    }

    if (vtt.length() == 0) {
      throw new SpeechToTextEngineException("WaaS produced no output");
    }
    return new Result(subtitleLanguage, vtt);
  }

  public enum TranscriptionState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
  }

  public static class TranscriptionJob {
    private String id;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class HTTPRequestMultipartBody {

    private final byte[] bytes;

    public String getBoundary() {
      return boundary;
    }

    public void setBoundary(String boundary) {
      this.boundary = boundary;
    }

    private String boundary;
    private HTTPRequestMultipartBody(byte[] bytes, String boundary) {
      this.bytes = bytes;
      this.boundary = boundary;
    }

    public String getContentType() {
      return "multipart/form-data; boundary=" + this.getBoundary();
    }
    public byte[] getBody() {
      return this.bytes;
    }

    public static class Builder {

      private static final String DEFAULT_MIMETYPE = "text/plain";

      public static class MultiPartRecord {
        private String fieldName;
        private String filename;
        private String contentType;
        private Object content;

        public String getFieldName() {
          return fieldName;
        }

        public void setFieldName(String fieldName) {
          this.fieldName = fieldName;
        }

        public String getFilename() {
          return filename;
        }

        public void setFilename(String filename) {
          this.filename = filename;
        }

        public String getContentType() {
          return contentType;
        }

        public void setContentType(String contentType) {
          this.contentType = contentType;
        }

        public Object getContent() {
          return content;
        }

        public void setContent(Object content) {
          this.content = content;
        }
      }

      private final List<MultiPartRecord> parts;

      public Builder() {
        this.parts = new ArrayList<>();
      }

      public Builder addPart(String fieldName, String fieldValue) {
        MultiPartRecord part = new MultiPartRecord();
        part.setFieldName(fieldName);
        part.setContent(fieldValue);
        part.setContentType(DEFAULT_MIMETYPE);
        this.parts.add(part);
        return this;
      }

      public Builder addPart(String fieldName, String fieldValue, String contentType) {
        MultiPartRecord part = new MultiPartRecord();
        part.setFieldName(fieldName);
        part.setContent(fieldValue);
        part.setContentType(contentType);
        this.parts.add(part);
        return this;
      }

      public Builder addPart(String fieldName, Object fieldValue, String contentType, String fileName) {
        MultiPartRecord part = new MultiPartRecord();
        part.setFieldName(fieldName);
        part.setContent(fieldValue);
        part.setContentType(contentType);
        part.setFilename(fileName);
        this.parts.add(part);
        return this;
      }

      public HTTPRequestMultipartBody build() throws IOException {
        String boundary = new BigInteger(256, new SecureRandom()).toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (MultiPartRecord record : parts) {
          StringBuilder stringBuilder = new StringBuilder();
          stringBuilder
              .append("--")
              .append(boundary)
              .append("\r\n")
              .append("Content-Disposition: form-data; name=\"")
              .append(record.getFieldName());
          if (record.getFilename() != null) {
            stringBuilder.append("\"; filename=\"").append(record.getFilename());
          }
          out.write(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
          out.write(("\"\r\n").getBytes(StandardCharsets.UTF_8));
          Object content = record.getContent();
          if (content instanceof String) {
            out.write(("\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(((String) content).getBytes(StandardCharsets.UTF_8));
          }
          else if (content instanceof byte[]) {
            out.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write((byte[]) content);
          } else if (content instanceof File) {
            out.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            Files.copy(((File) content).toPath(), out);
          } else {
            out.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(out);
            objectOutputStream.writeObject(content);
            objectOutputStream.flush();
          }
          out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return new HTTPRequestMultipartBody(out.toByteArray(), boundary);
      }

    }
  }

}
