ALTER TABLE `tb_voucher_order`
    ADD UNIQUE INDEX `uk_voucher_order_user_voucher` (`user_id`, `voucher_id`);
