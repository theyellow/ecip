package io.emcip.knowledge.engine.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.knowledge.engine.model.NodeSimilarityResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class GraphNodeEmbeddingRepositoryTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private GraphNodeEmbeddingRepository repository;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new GraphNodeEmbeddingRepository(jdbcTemplate);
    }

    @Test
    void findEmbedding_returnsEmptyWhenNoRowMatches() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<float[]> result = repository.findEmbedding("Acme", "ORGANIZATION", tenantId);

        assertThat(result).isEmpty();
    }

    @Test
    void findEmbedding_bindsTenantIdIntoQuery() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn("[0.1,0.2]");

        Optional<float[]> result = repository.findEmbedding("Acme", "ORGANIZATION", tenantId);

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(0.1f, 0.2f);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), any(Class.class), args.capture());
        assertThat(args.getValue()).contains(tenantId);
    }

    @Test
    void findEmbedding_nullTenant_omitsTenantIdFromBoundArgs() {
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn("[0.5]");

        repository.findEmbedding("Acme", "ORGANIZATION", null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(anyString(), any(Class.class), args.capture());
        assertThat(args.getValue()).doesNotContain((Object) tenantId).hasSize(2);
    }

    @Test
    void storeEmbedding_bindsTenantIdIntoUpdate() {
        float[] embedding = {0.1f, 0.2f, 0.3f};

        repository.storeEmbedding("Acme", "ORGANIZATION", tenantId, embedding);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).contains(tenantId);
    }

    @Test
    void storeEmbedding_nullTenant_omitsTenantIdFromBoundArgs() {
        float[] embedding = {0.1f, 0.2f};

        repository.storeEmbedding("Acme", "ORGANIZATION", null, embedding);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("tenant_id IS NULL");
        assertThat(args.getValue()).doesNotContain((Object) tenantId);
    }

    @Test
    void storeEmbedding_swallowsExceptionFromJdbcTemplate() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        float[] embedding = {0.1f};

        org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                                repository.storeEmbedding(
                                        "Acme", "ORGANIZATION", tenantId, embedding))
                .doesNotThrowAnyException();
    }

    @Test
    void findNearestNeighbour_bindsTenantIdAndReturnsMappedResult() {
        NodeSimilarityResult mapped = new NodeSimilarityResult(UUID.randomUUID(), "Acme", 0.9);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(mapped));

        Optional<NodeSimilarityResult> result =
                repository.findNearestNeighbour(new float[] {0.1f, 0.2f}, "ORGANIZATION", tenantId);

        assertThat(result).contains(mapped);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        assertThat(args.getValue()).contains(tenantId);
    }

    @Test
    void findNearestNeighbour_returnsEmptyWhenNoRowsFound() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        Optional<NodeSimilarityResult> result =
                repository.findNearestNeighbour(new float[] {0.1f}, "ORGANIZATION", tenantId);

        assertThat(result).isEmpty();
    }

    @Test
    void findNearestNeighbour_returnsEmptyWhenQueryThrows() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("db error"));

        Optional<NodeSimilarityResult> result =
                repository.findNearestNeighbour(new float[] {0.1f}, "ORGANIZATION", tenantId);

        assertThat(result).isEmpty();
    }

    @Test
    void findNearestNeighbour_nullTenant_omitsTenantIdFromBoundArgs() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.findNearestNeighbour(new float[] {0.1f}, "ORGANIZATION", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("tenant_id IS NULL");
        assertThat(args.getValue()).doesNotContain((Object) tenantId);
    }

    @Test
    void findSimilarNodes_bindsTenantIdAndReturnsMappedResults() {
        NodeSimilarityResult mapped = new NodeSimilarityResult(UUID.randomUUID(), "Acme", 0.9);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(mapped));

        List<NodeSimilarityResult> result =
                repository.findSimilarNodes(new float[] {0.1f, 0.2f}, tenantId, 5);

        assertThat(result).containsExactly(mapped);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        assertThat(args.getValue()).contains(tenantId);
    }

    @Test
    void findSimilarNodes_returnsEmptyWhenNoRowsFound() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        List<NodeSimilarityResult> result =
                repository.findSimilarNodes(new float[] {0.1f}, tenantId, 5);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilarNodes_returnsEmptyWhenQueryThrows() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("db error"));

        List<NodeSimilarityResult> result =
                repository.findSimilarNodes(new float[] {0.1f}, tenantId, 5);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilarNodes_nullTenant_omitsTenantIdFromBoundArgs() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.findSimilarNodes(new float[] {0.1f}, null, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).doesNotContain("tenant_id");
        assertThat(args.getValue()).doesNotContain((Object) tenantId).hasSize(3);
    }
}
