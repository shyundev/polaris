/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.persistence.nosql.dynamodb;

import static java.util.Collections.singletonMap;
import static org.apache.polaris.persistence.nosql.api.backend.PersistId.persistId;
import static org.apache.polaris.persistence.nosql.impl.Identifiers.COL_OBJ_CREATED_AT;
import static org.apache.polaris.persistence.nosql.impl.Identifiers.COL_OBJ_REAL_PART_NUM;
import static org.apache.polaris.persistence.nosql.impl.Identifiers.COL_OBJ_TYPE;
import static org.apache.polaris.persistence.nosql.impl.Identifiers.COL_OBJ_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.polaris.persistence.nosql.api.backend.PersistId;
import org.apache.polaris.persistence.nosql.api.exceptions.UnknownOperationResultException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

class DynamoDbBackendTest {

  @Test
  void conditionalCheckFailureAfterRetryHasUnknownOutcome() {
    var exception = ConditionalCheckFailedException.builder().numAttempts(2).build();

    var thrown =
        catchThrowable(() -> DynamoDbBackend.throwIfAmbiguousConditionalCheckFailure(exception));

    assertThat(thrown).isInstanceOf(UnknownOperationResultException.class);
    assertThat(thrown.getCause()).isSameAs(exception);
  }

  @Test
  void singleAttemptConditionalCheckFailureHasKnownOutcome() {
    var exception = ConditionalCheckFailedException.builder().numAttempts(1).build();

    assertThatCode(() -> DynamoDbBackend.throwIfAmbiguousConditionalCheckFailure(exception))
        .doesNotThrowAnyException();
  }

  @Test
  void batchWriteItemWithRetryResendsUnprocessedItems() {
    var client = mock(DynamoDbClient.class);
    var backend =
        spy(new DynamoDbBackend(new DynamoDbBackendConfig(false, client, Optional.empty())));
    doNothing().when(backend).sleepBeforeBatchRetry(anyInt());

    var requestItems = singletonMap("objs", List.of(sampleWriteRequest("k1")));
    var unprocessed = singletonMap("objs", List.of(sampleWriteRequest("k1")));

    when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
        .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(unprocessed).build())
        .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(Map.of()).build());

    backend.batchWriteItemWithRetry(requestItems);

    verify(client, times(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    verify(backend).sleepBeforeBatchRetry(0);
  }

  @Test
  void batchWriteItemWithRetryThrowsAfterMaxAttempts() {
    var client = mock(DynamoDbClient.class);
    var backend =
        spy(new DynamoDbBackend(new DynamoDbBackendConfig(false, client, Optional.empty())));
    doNothing().when(backend).sleepBeforeBatchRetry(anyInt());

    var requestItems = singletonMap("objs", List.of(sampleWriteRequest("k1")));
    var unprocessed = singletonMap("objs", List.of(sampleWriteRequest("k1")));
    when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
        .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(unprocessed).build());

    assertThatThrownBy(() -> backend.batchWriteItemWithRetry(requestItems))
        .isInstanceOf(UnknownOperationResultException.class)
        .hasMessageContaining("unprocessed items");

    verify(client, times(DynamoDbBackend.MAX_BATCH_ATTEMPTS))
        .batchWriteItem(any(BatchWriteItemRequest.class));
    verify(backend, times(DynamoDbBackend.MAX_BATCH_ATTEMPTS - 1)).sleepBeforeBatchRetry(anyInt());
  }

  @Test
  void fetchResendsUnprocessedKeys() {
    var client = mock(DynamoDbClient.class);
    var backend =
        spy(new DynamoDbBackend(new DynamoDbBackendConfig(false, client, Optional.empty())));
    doNothing().when(backend).sleepBeforeBatchRetry(anyInt());

    var id = persistId(42L, 0);
    var unprocessed =
        singletonMap(
            backend.tableObjs,
            KeysAndAttributes.builder()
                .keys(List.of(DynamoDbBackend.objKeyMap("realm", id)))
                .build());
    when(client.batchGetItem(any(BatchGetItemRequest.class)))
        .thenReturn(BatchGetItemResponse.builder().unprocessedKeys(unprocessed).build())
        .thenReturn(
            BatchGetItemResponse.builder()
                .responses(singletonMap(backend.tableObjs, List.of(sampleObjItem("realm", id))))
                .build());

    assertThat(backend.fetch("realm", Set.of(id))).containsOnlyKeys(id);
    verify(client, times(2)).batchGetItem(any(BatchGetItemRequest.class));
    verify(backend).sleepBeforeBatchRetry(0);
  }

  @Test
  void fetchThrowsWhenKeysStayUnprocessed() {
    var client = mock(DynamoDbClient.class);
    var backend =
        spy(new DynamoDbBackend(new DynamoDbBackendConfig(false, client, Optional.empty())));
    doNothing().when(backend).sleepBeforeBatchRetry(anyInt());

    var id = persistId(42L, 0);
    var unprocessed =
        singletonMap(
            backend.tableObjs,
            KeysAndAttributes.builder()
                .keys(List.of(DynamoDbBackend.objKeyMap("realm", id)))
                .build());
    when(client.batchGetItem(any(BatchGetItemRequest.class)))
        .thenReturn(BatchGetItemResponse.builder().unprocessedKeys(unprocessed).build());

    assertThatThrownBy(() -> backend.fetch("realm", Set.of(id)))
        .isInstanceOf(UnknownOperationResultException.class)
        .hasMessageContaining("unprocessed keys");

    verify(client, times(DynamoDbBackend.MAX_BATCH_ATTEMPTS))
        .batchGetItem(any(BatchGetItemRequest.class));
  }

  private static Map<String, AttributeValue> sampleObjItem(String realm, PersistId id) {
    var item = new HashMap<>(DynamoDbBackend.objKeyMap(realm, id));
    item.put(COL_OBJ_TYPE, AttributeValue.fromS("test"));
    item.put(COL_OBJ_REAL_PART_NUM, AttributeValue.fromN("1"));
    item.put(COL_OBJ_CREATED_AT, AttributeValue.fromN("0"));
    item.put(COL_OBJ_VALUE, AttributeValue.fromB(SdkBytes.fromByteArray(new byte[] {1})));
    return item;
  }

  private static WriteRequest sampleWriteRequest(String key) {
    return WriteRequest.builder()
        .putRequest(
            PutRequest.builder().item(Map.of("k", AttributeValue.builder().s(key).build())).build())
        .build();
  }
}
