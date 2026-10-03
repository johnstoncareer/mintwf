-- The call activity that started an instance, and the root of its call tree. Instances that nothing called have no
-- parent and are their own root.
ALTER TABLE mintwf_instance ADD COLUMN parent_instance_id VARCHAR(64);

ALTER TABLE mintwf_instance ADD COLUMN parent_execution_id VARCHAR(64);

ALTER TABLE mintwf_instance ADD COLUMN parent_node_id VARCHAR(255);

ALTER TABLE mintwf_instance ADD COLUMN root_instance_id VARCHAR(64);

UPDATE mintwf_instance SET root_instance_id = id;

CREATE INDEX mintwf_instance_parent ON mintwf_instance (parent_instance_id);
