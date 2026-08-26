package ccn.distribuidora.ecommerce.service;

import ccn.distribuidora.ecommerce.domain.entity.Produto;
import ccn.distribuidora.ecommerce.domain.entity.VtexPriceDTO;
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
public class SincronizacaoService {

    private static final Logger logger = LoggerFactory.getLogger(SincronizacaoService.class);

    private final RestClient restClient;
    private final JdbcTemplate jdbcTemplate;

    // Configurações extraídas do seu Axios
    private final String API_SITE = "https://api.vtex.com/ccndistribuidora/pricing/prices";
    private final String VTEX_APP_KEY = "vtexappkey-ccndistribuidora-VNIRPT";
    private final String VTEX_APP_TOKEN = "PLUNCPDBYUXWCKAURVDWUREOOTVTBFYWSBIVIJGTSRTPYWJMCYSKUWJOAKCCVYGQPJVMVOESJDSEYXUARJPIXPEOGNZEFRKCAAZJKECRVEUTZZEAHSNCUEIXCAWWDZOB";

    private final Map<Integer, Produto> memoriaPrecos = new ConcurrentHashMap<>();
    private boolean primeiraExecucao = true;

    public SincronizacaoService(JdbcTemplate jdbcTemplate) {
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
            logger.error("Erro ao configurar bypass de SSL: {}", e.getMessage());
            return RestClient.create();
        }
    }

    @Scheduled(cron = "0 * * * * *") // Roda a cada 1 minuto
    public void executarSincronizacao() {
        logger.info("Lendo VW_ECOMMERCE_PRECOS no banco de dados...");

        try {
            String sql = "SELECT RefId, basePrice, costPrice FROM VW_ECOMMERCE_PRECOS";
            List<Produto> produtosDoBanco = jdbcTemplate.query(sql, (rs, rowNum) -> new Produto(
                    rs.getInt("RefId"),
                    rs.getDouble("basePrice"),
                    rs.getDouble("costPrice")
            ));

            if (primeiraExecucao) {
                for (Produto p : produtosDoBanco) {
                    memoriaPrecos.put(p.refId(), p);
                }
                primeiraExecucao = false;
                logger.info("Memória inicial carregada com {} produtos.", produtosDoBanco.size());
                return;
            }

            int produtosAtualizados = 0;

            for (Produto produtoAtual : produtosDoBanco) {
                Produto produtoAntigo = memoriaPrecos.get(produtoAtual.refId());

                if (produtoAntigo == null || !produtoAntigo.equals(produtoAtual)) {

                    try {
                        String urlProduto = API_SITE + "/" + produtoAtual.refId();

                        // Montando o DTO com o formato exato que a VTEX exige
                        VtexPriceDTO corpoVtex = new VtexPriceDTO(
                                produtoAtual.basePrice(),
                                produtoAtual.costPrice(),
                                produtoAtual.basePrice()
                        );

                        restClient.put()
                                .uri(urlProduto)
                                .header("X-VTEX-API-AppKey", VTEX_APP_KEY)
                                .header("X-VTEX-API-AppToken", VTEX_APP_TOKEN)
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .body(corpoVtex)
                                .retrieve()
                                .toBodilessEntity();

                        memoriaPrecos.put(produtoAtual.refId(), produtoAtual);
                        produtosAtualizados++;

                        logger.info("SKU {} atualizado com sucesso na VTEX.", produtoAtual.refId());

                    } catch (Exception e) {
                        logger.error("Falha ao enviar SKU {}: {}", produtoAtual.refId(), e.getMessage());
                    }
                }
            }

            logger.info("Ciclo finalizado. Total de atualizações enviadas: {}", produtosAtualizados);

        } catch (Exception e) {
            logger.error("Erro ao acessar o banco de dados: {}", e.getMessage());
        }
    }
}