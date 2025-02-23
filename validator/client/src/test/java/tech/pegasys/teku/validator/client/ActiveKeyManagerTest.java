/*
 * Copyright ConsenSys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.validator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.spec.generator.signatures.NoOpLocalSigner.NO_OP_SIGNER;
import static tech.pegasys.teku.spec.generator.signatures.NoOpRemoteSigner.NO_OP_REMOTE_SIGNER;

import com.google.common.io.Resources;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSTestUtil;
import tech.pegasys.teku.data.SlashingProtectionIncrementalExporter;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.signatures.Signer;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.validator.api.ValidatorTimingChannel;
import tech.pegasys.teku.validator.client.loader.OwnedValidators;
import tech.pegasys.teku.validator.client.loader.ValidatorLoader;
import tech.pegasys.teku.validator.client.restapi.apis.schema.DeleteKeyResult;
import tech.pegasys.teku.validator.client.restapi.apis.schema.DeleteKeysResponse;
import tech.pegasys.teku.validator.client.restapi.apis.schema.DeletionStatus;
import tech.pegasys.teku.validator.client.restapi.apis.schema.ExternalValidator;
import tech.pegasys.teku.validator.client.restapi.apis.schema.PostKeyResult;

class ActiveKeyManagerTest {

  private final DataStructureUtil dataStructureUtil =
      new DataStructureUtil(TestSpecFactory.createMinimalAltair());
  private final ValidatorLoader validatorLoader = mock(ValidatorLoader.class);
  private final OwnedValidators ownedValidators = mock(OwnedValidators.class);
  private final BLSPublicKey publicKey = dataStructureUtil.randomPublicKey();
  private final SlashingProtectionIncrementalExporter exporter =
      mock(SlashingProtectionIncrementalExporter.class);
  private final Signer signer = mock(Signer.class);
  private final ValidatorTimingChannel channel = mock(ValidatorTimingChannel.class);
  private final ActiveKeyManager keyManager = new ActiveKeyManager(validatorLoader, channel);

  @Test
  void shouldReturnNotFoundIfSlashingProtectionNotFound() {
    when(exporter.haveSlashingProtectionData(publicKey)).thenReturn(false);

    final DeleteKeyResult result =
        keyManager.attemptToGetSlashingDataForInactiveValidator(publicKey, exporter);
    assertThat(result.getStatus()).isEqualTo(DeletionStatus.NOT_FOUND);
  }

  @Test
  void shouldCallValidatorsAddedOnSuccessfulImport() throws URISyntaxException, IOException {
    final String data = getKeystore();

    when(validatorLoader.loadLocalMutableValidator(any(), any(), any()))
        .thenReturn(PostKeyResult.success());
    keyManager.importValidators(List.of(data), List.of("testpassword"), Optional.empty());
    verify(channel, times(1)).onValidatorsAdded();
  }

  @Test
  void shouldNotCallValidatorsAddedOnUnsuccessfulImport() throws URISyntaxException, IOException {
    final String data = getKeystore();

    when(validatorLoader.loadLocalMutableValidator(any(), any(), any()))
        .thenReturn(PostKeyResult.duplicate());
    keyManager.importValidators(List.of(data), List.of("testpassword"), Optional.empty());
    verify(channel, never()).onValidatorsAdded();
  }

  @Test
  void shouldReturnNotActiveIfSlashingProtectionFound() {
    when(exporter.haveSlashingProtectionData(publicKey)).thenReturn(true);

    final DeleteKeyResult result =
        keyManager.attemptToGetSlashingDataForInactiveValidator(publicKey, exporter);
    assertThat(result.getStatus()).isEqualTo(DeletionStatus.NOT_ACTIVE);
  }

  @Test
  void deleteValidator_shouldStopAndDeleteValidator() {
    final Validator activeValidator = mock(Validator.class);

    when(activeValidator.getPublicKey()).thenReturn(publicKey);
    when(activeValidator.isReadOnly()).thenReturn(false);
    when(activeValidator.getSigner()).thenReturn(signer);
    when(exporter.addPublicKeyToExport(eq(publicKey), any())).thenReturn(Optional.empty());
    when(validatorLoader.deleteLocalMutableValidator(publicKey))
        .thenReturn(DeleteKeyResult.success());

    final DeleteKeyResult result = keyManager.deleteValidator(activeValidator, exporter);
    verify(signer).delete();
    verify(validatorLoader).deleteLocalMutableValidator(publicKey);
    assertThat(result.getStatus()).isEqualTo(DeletionStatus.DELETED);
    assertThat(result.getMessage()).isEmpty();
    verify(channel, never()).onValidatorsAdded();
  }

  @Test
  void deleteValidator_shouldReportSlashingExportError(@TempDir final Path tempDir)
      throws IOException {
    Files.createFile(
        tempDir.resolve(publicKey.toBytesCompressed().toUnprefixedHexString() + ".yml"));
    final SlashingProtectionIncrementalExporter exporter =
        new SlashingProtectionIncrementalExporter(tempDir);
    final Validator activeValidator = mock(Validator.class);

    when(activeValidator.getPublicKey()).thenReturn(publicKey);
    when(activeValidator.isReadOnly()).thenReturn(false);
    when(activeValidator.getSigner()).thenReturn(signer);
    when(validatorLoader.deleteLocalMutableValidator(publicKey))
        .thenReturn(DeleteKeyResult.success());

    final DeleteKeyResult result = keyManager.deleteValidator(activeValidator, exporter);
    verify(signer).delete();
    verify(validatorLoader).deleteLocalMutableValidator(publicKey);
    assertThat(result.getStatus()).isEqualTo(DeletionStatus.ERROR);
    assertThat(result.getMessage().orElse("")).startsWith("Failed to read from file");
    verify(channel, never()).onValidatorsAdded();
  }

  @Test
  void deleteValidators_shouldStopAndDeleteValidator(@TempDir final Path tempDir) {
    final Validator activeValidator = mock(Validator.class);

    when(activeValidator.getPublicKey()).thenReturn(publicKey);
    when(activeValidator.isReadOnly()).thenReturn(false);
    when(activeValidator.getSigner()).thenReturn(signer);
    when(validatorLoader.getOwnedValidators())
        .thenReturn(new OwnedValidators(Map.of(publicKey, activeValidator)));
    when(exporter.addPublicKeyToExport(eq(publicKey), any())).thenReturn(Optional.empty());
    when(validatorLoader.deleteLocalMutableValidator(publicKey))
        .thenReturn(DeleteKeyResult.success());

    final DeleteKeysResponse response = keyManager.deleteValidators(List.of(publicKey), tempDir);
    verify(signer).delete();
    verify(validatorLoader).deleteLocalMutableValidator(publicKey);

    assertThat(response.getData().get(0).getStatus()).isEqualTo(DeletionStatus.DELETED);
    assertThat(response.getData()).hasSize(1);
    assertThat(response.getSlashingProtection()).isNotEmpty();
    verify(channel, never()).onValidatorsAdded();
  }

  @Test
  void deleteValidators_shouldRejectRequestToDeleteReadOnlyValidator(@TempDir final Path tempDir) {
    final Validator activeValidator = mock(Validator.class);

    when(activeValidator.isReadOnly()).thenReturn(true);
    when(validatorLoader.getOwnedValidators())
        .thenReturn(new OwnedValidators(Map.of(publicKey, activeValidator)));
    when(activeValidator.getPublicKey()).thenReturn(publicKey);

    final DeleteKeysResponse response = keyManager.deleteValidators(List.of(publicKey), tempDir);
    assertThat(response.getData()).hasSize(1);
    assertThat(response.getData().get(0).getStatus()).isEqualTo(DeletionStatus.ERROR);
    assertThat(response.getData().get(0).getMessage().orElse("")).contains("read-only");
    assertThat(response.getSlashingProtection()).isNotEmpty();
    verify(channel, never()).onValidatorsAdded();
  }

  @Test
  void shouldReturnActiveValidatorsList() {
    final List<Validator> validatorsList = generateActiveValidatorsList();
    when(ownedValidators.getActiveValidators()).thenReturn(validatorsList);
    when(validatorLoader.getOwnedValidators()).thenReturn(ownedValidators);
    final List<Validator> activeValidatorList = keyManager.getActiveValidatorKeys();

    assertThat(activeValidatorList).isEqualTo(validatorsList);
    verify(channel, never()).onValidatorsAdded();
  }

  @Test
  void shouldReturnEmptyKeyList() {
    final List<Validator> validatorsList = Collections.emptyList();
    when(ownedValidators.getActiveValidators()).thenReturn(validatorsList);
    when(validatorLoader.getOwnedValidators()).thenReturn(ownedValidators);
    final List<Validator> activeValidatorList = keyManager.getActiveValidatorKeys();

    assertThat(activeValidatorList).isEmpty();
  }

  private List<Validator> generateActiveValidatorsList() {
    final BLSKeyPair keyPair1 = BLSTestUtil.randomKeyPair(1);
    final BLSKeyPair keyPair2 = BLSTestUtil.randomKeyPair(2);
    final Validator validator1 =
        new Validator(keyPair1.getPublicKey(), NO_OP_SIGNER, Optional::empty, true);
    final Validator validator2 =
        new Validator(keyPair2.getPublicKey(), NO_OP_SIGNER, Optional::empty, false);
    return Arrays.asList(validator1, validator2);
  }

  @Test
  void shouldReturnActiveRemoteValidatorsList() throws MalformedURLException {
    final BLSKeyPair keyPair1 = BLSTestUtil.randomKeyPair(1);
    final BLSKeyPair keyPair2 = BLSTestUtil.randomKeyPair(2);
    final BLSKeyPair keyPair3 = BLSTestUtil.randomKeyPair(3);
    final Validator validator1 =
        new Validator(keyPair1.getPublicKey(), NO_OP_REMOTE_SIGNER, Optional::empty, true);
    final Validator validator2 =
        new Validator(keyPair2.getPublicKey(), NO_OP_REMOTE_SIGNER, Optional::empty, false);
    final Validator validator3 =
        new Validator(keyPair3.getPublicKey(), NO_OP_SIGNER, Optional::empty, false);
    List<Validator> activeValidators = Arrays.asList(validator1, validator2, validator3);

    when(ownedValidators.getActiveValidators()).thenReturn(activeValidators);
    when(validatorLoader.getOwnedValidators()).thenReturn(ownedValidators);
    final List<ExternalValidator> result = keyManager.getActiveRemoteValidatorKeys();

    List<ExternalValidator> externalValidators =
        Arrays.asList(
            new ExternalValidator(
                keyPair1.getPublicKey(), Optional.of(new URL("http://example.com/")), true),
            new ExternalValidator(
                keyPair2.getPublicKey(), Optional.of(new URL("http://example.com/")), false));
    assertThat(result).isEqualTo(externalValidators);
  }

  @Test
  void shouldReturnEmptyRemoteKeyList() {
    final List<Validator> validatorsList = Collections.emptyList();
    when(ownedValidators.getActiveValidators()).thenReturn(validatorsList);
    when(validatorLoader.getOwnedValidators()).thenReturn(ownedValidators);
    final List<ExternalValidator> activeValidatorList = keyManager.getActiveRemoteValidatorKeys();

    assertThat(activeValidatorList).isEmpty();
  }

  private String getKeystore() throws IOException, URISyntaxException {
    final URL resource = Resources.getResource("pbkdf2TestVector.json");
    FileInputStream fis = new FileInputStream(new File(resource.toURI()));
    return IOUtils.toString(fis, "UTF-8");
  }
}
