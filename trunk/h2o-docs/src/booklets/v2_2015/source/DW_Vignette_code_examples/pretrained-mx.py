model = H2ODeepWaterEstimator(epochs=100, image_shape=[28,28], backend="mxnet", network="user", network_definition_file="/path/to/lenet.json", network_parameters_file="/path/to/lenet-100epochs-params.txt")
