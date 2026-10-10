-- Disposable benchmark fixture: keep the 14 original shops and extend the working set to 20,000.
-- The JMeter plan maps 70% of traffic to ids 1-100, 20% to 101-2,000 and 10% to 2,001-20,000.
DELIMITER $$

DROP PROCEDURE IF EXISTS prepare_hot_shop_benchmark$$
CREATE PROCEDURE prepare_hot_shop_benchmark()
BEGIN
    DECLARE shop_id BIGINT DEFAULT 15;

    START TRANSACTION;
    WHILE shop_id <= 20000 DO
        INSERT INTO tb_shop (
            id, name, type_id, images, area, address, x, y, avg_price,
            sold, comments, score, open_hours, create_time, update_time
        )
        SELECT
            shop_id,
            CONCAT('Benchmark Shop ', shop_id),
            type_id,
            images,
            area,
            CONCAT('Benchmark Address ', shop_id),
            x,
            y,
            avg_price,
            sold,
            comments,
            score,
            open_hours,
            NOW(),
            NOW()
        FROM tb_shop
        WHERE id = 1
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            address = VALUES(address),
            update_time = VALUES(update_time);

        SET shop_id = shop_id + 1;
    END WHILE;
    COMMIT;
END$$

CALL prepare_hot_shop_benchmark()$$
DROP PROCEDURE prepare_hot_shop_benchmark$$

DELIMITER ;
