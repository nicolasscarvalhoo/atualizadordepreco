package ccn.distribuidora.ecommerce.service;

import ccn.distribuidora.ecommerce.domain.entity.EstoqueProduto;
import ccn.distribuidora.ecommerce.domain.entity.VtexEstoqueDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SincronizacaoEstoqueService {

    private static final Logger logger = LoggerFactory.getLogger(SincronizacaoEstoqueService.class);

    private final RestClient restClient;
    private final JdbcTemplate jdbcTemplate;

    // ID do seu Armazém na VTEX (Verifique no painel e altere se necessário)
    private final String WAREHOUSE_ID = "1_1";

    // URLs da VTEX
    private final String API_LOGISTICS = "https://ccndistribuidora.vtexcommercestable.com.br/api/logistics/pvt/inventory/skus/";
    private final String API_CATALOG_REFID = "https://ccndistribuidora.vtexcommercestable.com.br/api/catalog_system/pvt/sku/stockkeepingunitidbyrefid/";

    // Credenciais
    private final String VTEX_APP_KEY = "vtexappkey-ccndistribuidora-VNIRPT";
    private final String VTEX_APP_TOKEN = "PLUNCPDBYUXWCKAURVDWUREOOTVTBFYWSBIVIJGTSRTPYWJMCYSKUWJOAKCCVYGQPJVMVOESJDSEYXUARJPIXPEOGNZEFRKCAAZJKECRVEUTZZEAHSNCUEIXCAWWDZOB";

    private final Map<Integer, EstoqueProduto> memoriaEstoque = new ConcurrentHashMap<>();
    private boolean primeiraExecucao = true;

    public SincronizacaoEstoqueService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restClient = criarRestClientIgnorandoSsl();
    }

    private RestClient criarRestClientIgnorandoSsl() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            return RestClient.builder()
                    .requestFactory(factory)
                    .defaultHeader("User-Agent", "Mozilla/5.0")
                    .build();
        } catch (Exception e) {
            return RestClient.create();
        }
    }

    // Roda a cada 1 minuto (pode colocar outro tempo se quiser descolar do atualizador de preços)
    @Scheduled(cron = "0 * * * * *")
    public void executarSincronizacaoEstoque() {
        logger.info("[ESTOQUE] Lendo VW_ECOMMERCE_ESTOQUE no banco de dados...");

        try {
            String sql = "SELECT RefId, quantidade FROM VW_ECOMMERCE_ESTOQUE";
            List<EstoqueProduto> produtosDoBanco = jdbcTemplate.query(sql, (rs, rowNum) -> new EstoqueProduto(
                    rs.getInt("RefId"),
                    rs.getInt("quantidade")
            ));

            if (primeiraExecucao) {
                for (EstoqueProduto p : produtosDoBanco) {
                    memoriaEstoque.put(p.refId(), p);
                }
                primeiraExecucao = false;
                logger.info("[ESTOQUE] Memória inicial carregada com {} produtos.", produtosDoBanco.size());
                return;
            }

            int produtosAtualizados = 0;

            for (EstoqueProduto produtoAtual : produtosDoBanco) {
                EstoqueProduto produtoAntigo = memoriaEstoque.get(produtoAtual.refId());

                if (produtoAntigo == null || !produtoAntigo.equals(produtoAtual)) {

                    try {
                        // PASSO 1: Traduzir RefId para VTEX ID
                        String vtexSkuId = "";
                        try {
                            vtexSkuId = restClient.get()
                                    .uri(API_CATALOG_REFID + produtoAtual.refId())
                                    .header("X-VTEX-API-AppKey", VTEX_APP_KEY)
                                    .header("X-VTEX-API-AppToken", VTEX_APP_TOKEN)
                                    .header("Accept", "application/json")
                                    .retrieve()
                                    .body(String.class);

                            if (vtexSkuId != null) {
                                vtexSkuId = vtexSkuId.replace("\"", "").trim();
                            }
                        } catch (Exception e) {
                            continue; // Ignora se não existir na VTEX
                        }

                        // PASSO 2: Enviar saldo para o Armazém
                        if (vtexSkuId != null && !vtexSkuId.isEmpty()) {

                            // A URL de estoque exige o SkuId e o WarehouseId
                            String urlEstoque = API_LOGISTICS + vtexSkuId + "/warehouses/" + WAREHOUSE_ID;

                            // O DTO do estoque: Quantidade e se é infinito (false)
                            VtexEstoqueDTO corpoVtex = new VtexEstoqueDTO(
                                    produtoAtual.quantidade(),
                                    false
                            );

                            restClient.put()
                                    .uri(urlEstoque)
                                    .header("X-VTEX-API-AppKey", VTEX_APP_KEY)
                                    .header("X-VTEX-API-AppToken", VTEX_APP_TOKEN)
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                    .body(corpoVtex)
                                    .retrieve()
                                    .toBodilessEntity();

                            memoriaEstoque.put(produtoAtual.refId(), produtoAtual);
                            produtosAtualizados++;

                            logger.info("[ESTOQUE] Sucesso! RefId {} (VTEX: {}) atualizado para {} un.",
                                    produtoAtual.refId(), vtexSkuId, produtoAtual.quantidade());
                        }

                    } catch (Exception e) {
                        logger.error("[ESTOQUE] Falha ao enviar estoque para RefId {}: {}", produtoAtual.refId(), e.getMessage());
                    }
                }
            }

            logger.info("[ESTOQUE] Ciclo finalizado. Total de atualizações enviadas: {}", produtosAtualizados);

        } catch (Exception e) {
            logger.error("[ESTOQUE] Erro ao acessar o banco de dados: {}", e.getMessage());
        }
    }
}