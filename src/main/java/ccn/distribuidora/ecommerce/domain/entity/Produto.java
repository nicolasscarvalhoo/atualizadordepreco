package ccn.distribuidora.ecommerce.domain.entity;

public record Produto(
        Integer refId,
        Double basePrice,
        Double costPrice
) {}