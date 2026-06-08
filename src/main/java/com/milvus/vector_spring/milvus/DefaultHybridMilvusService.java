package com.milvus.vector_spring.milvus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.milvus.vector_spring.common.apipayload.status.ErrorStatus;
import com.milvus.vector_spring.common.exception.CustomException;
import com.milvus.vector_spring.config.properties.MilvusProperties;
import com.milvus.vector_spring.milvus.dto.InsertRequestDto;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "milvus.search-mode", havingValue = "HYBRID")
public class DefaultHybridMilvusService implements MilvusService {

    private static final long SPARSE_DIM_BOUND = 1L << 20; // hash space: 0 ~ 1,048,575
    private static final String SPARSE_FIELD = "sparse_vector";

    private final MilvusProperties milvusProperties;
    private final MilvusClientV2 client;

    @Override
    public void createSchema(Long dbKey, int dimension) {
        try {
            List<String> existing = client.listCollections().getCollectionNames();
            if (existing.contains(collectionName(dbKey))) {
                throw new CustomException(ErrorStatus.MILVUS_COLLECTION_ALREADY_EXISTS);
            }

            // [수정] 구형 schema.addField() 방식 대신 Builder 내부에 필드 리스트를 직접 주입하도록 변경
            List<CreateCollectionReq.FieldSchema> fields = List.of(
                    CreateCollectionReq.FieldSchema.builder().name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false).build(),
                    CreateCollectionReq.FieldSchema.builder().name("vector").dataType(DataType.FloatVector).dimension(dimension).build(),
                    CreateCollectionReq.FieldSchema.builder().name(SPARSE_FIELD).dataType(DataType.SparseFloatVector).build(),
                    CreateCollectionReq.FieldSchema.builder().name("title").dataType(DataType.VarChar).maxLength(512).build(),
                    CreateCollectionReq.FieldSchema.builder().name("answer").dataType(DataType.VarChar).maxLength(65535).build()
            );

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(fields)
                    .build();

            List<IndexParam> indexes = List.of(
                    IndexParam.builder()
                            .fieldName("vector")
                            .indexType(IndexParam.IndexType.HNSW)
                            .metricType(IndexParam.MetricType.COSINE)
                            .extraParams(Map.of("efConstruction", 300, "M", 32))
                            .build(),
                    IndexParam.builder()
                            .fieldName(SPARSE_FIELD)
                            .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                            .metricType(IndexParam.MetricType.IP)
                            .build()
            );

            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName(dbKey))
                    .collectionSchema(schema)
                    .indexParams(indexes)
                    .build());

            client.getLoadState(GetLoadStateReq.builder().collectionName(collectionName(dbKey)).build());
            log.info("[HybridMilvus] Hybrid collection created: {}", collectionName(dbKey));
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[HybridMilvus] Schema creation failed: {}", e.getMessage());
            throw new CustomException(ErrorStatus.MILVUS_SCHEMA_CREATE_ERROR);
        }
    }

    @Override
    public boolean checkCollectionLoadState(Long dbKey) {
        return Boolean.TRUE.equals(
                client.getLoadState(GetLoadStateReq.builder().collectionName(collectionName(dbKey)).build())
        );
    }

    @Override
    public void upsertCollection(long id, InsertRequestDto dto, Long dbKey) {
        try {
            SparseFloatVec sparseVec = computeSparseVector(dto.title() + " " + dto.answer());

            JsonObject sparseJson = new JsonObject();
            @SuppressWarnings("unchecked")
            SortedMap<Long, Float> sparseData = (SortedMap<Long, Float>) sparseVec.getData();
            sparseData.forEach((dim, val) -> sparseJson.addProperty(String.valueOf(dim), val));

            JsonObject dataObject = new JsonObject();
            JsonArray vectorArray = new JsonArray();
            dto.vector().forEach(vectorArray::add);
            dataObject.addProperty("id", id);
            dataObject.add("vector", vectorArray);
            dataObject.add(SPARSE_FIELD, sparseJson);
            dataObject.addProperty("title", dto.title());
            dataObject.addProperty("answer", dto.answer());

            client.upsert(UpsertReq.builder()
                    .collectionName(collectionName(dbKey))
                    .data(List.of(dataObject))
                    .build());
        } catch (Exception e) {
            throw new CustomException(ErrorStatus.MILVUS_UPSERT_ERROR);
        }
    }

    @Override
    public void deleteCollection(long id) {
        try {
            client.dropCollection(DropCollectionReq.builder()
                    .collectionName(collectionName(id))
                    .build());
        } catch (Exception e) {
            throw new CustomException(ErrorStatus.MILVUS_DELETE_ERROR);
        }
    }

    @Override
    public void deleteDocument(long documentId, Long dbKey) {
        try {
            client.delete(DeleteReq.builder()
                    .collectionName(collectionName(dbKey))
                    .ids(List.of(documentId))
                    .build());
        } catch (Exception e) {
            throw new CustomException(ErrorStatus.MILVUS_DELETE_ERROR);
        }
    }

    @Override
    public boolean hasCollection() {
        return client.hasCollection(HasCollectionReq.builder()
                .collectionName(milvusProperties.collectionName())
                .build());
    }

    /**
     * Dense-only fallback. Hybrid search goes through hybridSearch().
     * This exists to satisfy the MilvusService contract for non-search operations.
     */
    @Override
    public SearchResp vectorSearch(List<Float> vectorData, Long dbKey) {
        try {
            List<BaseVector> baseVectors = new ArrayList<>();
            if (vectorData != null) {
                baseVectors.add(new FloatVec(new ArrayList<>(vectorData)));
            }
            return client.search(SearchReq.builder()
                    .collectionName(collectionName(dbKey))
                    .data(baseVectors)
                    .limit(milvusProperties.topK())
                    .searchParams(Map.of("metric_type", "COSINE", "ef", 300))
                    .outputFields(List.of("title", "answer"))
                    .build());
        } catch (Exception e) {
            throw new CustomException(ErrorStatus.MILVUS_VECTOR_SEARCH_ERROR);
        }
    }

    public SearchResp hybridSearch(List<Float> denseVector, String queryText, Long dbKey) {
        try {
            SparseFloatVec sparseQueryVec = computeSparseVector(queryText);

            AnnSearchReq denseReq = AnnSearchReq.builder()
                    .vectorFieldName("vector")
                    .vectors(List.of(new FloatVec(new ArrayList<>(denseVector))))
                    .params("{\"metric_type\": \"COSINE\", \"ef\": 300}")
                    .limit(milvusProperties.topK())
                    .build();

            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName(SPARSE_FIELD)
                    .vectors(List.of(sparseQueryVec))
                    .params("{\"metric_type\": \"IP\", \"drop_ratio_search\": 0.2}")
                    .limit(milvusProperties.topK())
                    .build();

            return client.hybridSearch(HybridSearchReq.builder()
                    .collectionName(collectionName(dbKey))
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(RRFRanker.builder().k(60).build())
                    .limit(milvusProperties.topK())
                    .outFields(List.of("title", "answer"))
                    .build());
        } catch (Exception e) {
            log.error("[HybridMilvus] Hybrid search failed: {}", e.getMessage());
            throw new CustomException(ErrorStatus.MILVUS_VECTOR_SEARCH_ERROR);
        }
    }

    /**
     * Computes a normalized TF sparse vector using hashed terms.
     * Hash space is bounded to SPARSE_DIM_BOUND to keep the sparse representation compact.
     * The same function is used at both insert and query time, ensuring consistent dimensions.
     */
    private SparseFloatVec computeSparseVector(String text) {
        if (text == null || text.isBlank()) {
            return new SparseFloatVec(new TreeMap<>(Map.of(0L, 0.0f)));
        }

        String[] tokens = text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9가-힣\\s]", " ")
                .trim()
                .split("\\s+");

        TreeMap<Long, Float> sparse = new TreeMap<>();
        for (String token : tokens) {
            if (token.length() < 2) continue;
            long dim = Math.abs((long) token.hashCode()) % SPARSE_DIM_BOUND;
            sparse.merge(dim, 1.0f, Float::sum);
        }

        if (sparse.isEmpty()) {
            return new SparseFloatVec(new TreeMap<>(Map.of(0L, 0.0f)));
        }

        float norm = (float) Math.sqrt(sparse.values().stream().mapToDouble(v -> v * v).sum());
        sparse.replaceAll((k, v) -> v / norm);

        return new SparseFloatVec(sparse);
    }

    private String collectionName(long dbKey) {
        return milvusProperties.collectionName() + dbKey;
    }
}