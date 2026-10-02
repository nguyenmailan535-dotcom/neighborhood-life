SET @voucher_id = 9001;
SET @stock = 100000;

DELETE FROM tb_voucher_order WHERE voucher_id = @voucher_id;
INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES (@voucher_id, @stock, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE
    stock = VALUES(stock),
    begin_time = VALUES(begin_time),
    end_time = VALUES(end_time);
