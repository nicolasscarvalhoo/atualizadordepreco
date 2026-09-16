package ccn.distribuidora.ecommerce.domain.entity;

public record VtexEstoqueDTO(
        Integer quantity,
        Boolean unlimitedQuantity
) {}