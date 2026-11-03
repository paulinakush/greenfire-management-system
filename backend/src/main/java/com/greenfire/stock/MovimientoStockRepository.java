package com.greenfire.stock;

import org.springframework.data.repository.Repository;

/** Solo inserción: los movimientos no se editan ni se borran. Las consultas van por {@link StockService}. */
public interface MovimientoStockRepository extends Repository<MovimientoStock, Long> {

    MovimientoStock save(MovimientoStock movimiento);
}
