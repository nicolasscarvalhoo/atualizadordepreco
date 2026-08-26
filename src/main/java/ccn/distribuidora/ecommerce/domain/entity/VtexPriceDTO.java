package ccn.distribuidora.ecommerce.domain.entity;

public record VtexPriceDTO(
        Double basePrice,
        Double costPrice,
        Double listPrice
) {}