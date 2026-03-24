add_library(core ${G2O_LIB_TYPE}
dynamic_aligned_buffer.hpp
ownership.h
base_edge.h
base_multi_edge.h
base_fixed_sized_edge.h        hyper_graph_action.cpp
base_fixed_sized_edge.hpp      hyper_graph_action.h
base_variable_sized_edge.h     hyper_graph.cpp
base_variable_sized_edge.hpp   hyper_graph.h
base_unary_edge.h           linear_solver.h
base_binary_edge.h          marginal_covariance_cholesky.cpp
base_vertex.h               marginal_covariance_cholesky.h
base_vertex.hpp             matrix_structure.cpp
batch_stats.cpp             matrix_structure.h
batch_stats.h               openmp_mutex.h
block_solver.h              block_solver.hpp
parameter.cpp               parameter.h
cache.cpp                   cache.h
optimizable_graph.cpp       optimizable_graph.h
solver.cpp                  solver.h
creators.h                  optimization_algorithm_factory.cpp
estimate_propagator.cpp     optimization_algorithm_factory.h
estimate_propagator.h
factory.cpp                 optimization_algorithm_property.h
factory.h                   sparse_block_matrix.h
sparse_optimizer.cpp  sparse_block_matrix.hpp
sparse_optimizer.h
hyper_dijkstra.cpp hyper_dijkstra.h
parameter_container.cpp     parameter_container.h
optimization_algorithm.cpp optimization_algorithm.h
optimization_algorithm_with_hessian.cpp optimization_algorithm_with_hessian.h
optimization_algorithm_gauss_newton.cpp optimization_algorithm_gauss_newton.h
optimization_algorithm_levenberg.cpp optimization_algorithm_levenberg.h
optimization_algorithm_dogleg.cpp optimization_algorithm_dogleg.h
sparse_optimizer_terminate_action.cpp sparse_optimizer_terminate_action.h
jacobian_workspace.cpp jacobian_workspace.h
robust_kernel.cpp robust_kernel.h
robust_kernel_impl.cpp robust_kernel_impl.h
robust_kernel_factory.cpp robust_kernel_factory.h
io_helper.h
g2o_core_api.h
)

add_library(g2o::core ALIAS core)

target_include_directories(core PUBLIC
  "$<BUILD_INTERFACE:${g2o_SOURCE_DIR};${PROJECT_BINARY_DIR}>"
  $<INSTALL_INTERFACE:include/g2o/core>
)

set_target_properties(core PROPERTIES OUTPUT_NAME ${LIB_PREFIX}core)
set_target_properties(core PROPERTIES CLEAN_DIRECT_OUTPUT 1)

target_link_libraries(core stuff)

install(TARGETS core EXPORT g2oTargets
  RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
  LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
  ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR})

install(FILES
  dynamic_aligned_buffer.hpp
  ownership.h
  base_edge.h
  base_multi_edge.h
  base_fixed_sized_edge.h
  base_fixed_sized_edge.hpp
  base_variable_sized_edge.h
  base_variable_sized_edge.hpp
  base_unary_edge.h
  linear_solver.h
  base_binary_edge.h
  marginal_covariance_cholesky.h
  base_vertex.h
  base_vertex.hpp
  matrix_structure.h
  batch_stats.h
  openmp_mutex.h
  block_solver.h
  block_solver.hpp
  parameter.h
  cache.h
  optimizable_graph.h
  solver.h
  creators.h
  optimization_algorithm_factory.h
  estimate_propagator.h
  optimization_algorithm_property.h
  factory.h
  sparse_block_matrix.h
  sparse_block_matrix.hpp
  sparse_optimizer.h
  hyper_dijkstra.h
  parameter_container.h
  optimization_algorithm.h
  optimization_algorithm_with_hessian.h
  optimization_algorithm_gauss_newton.h
  optimization_algorithm_levenberg.h
  optimization_algorithm_dogleg.h
  sparse_optimizer_terminate_action.h
  jacobian_workspace.h
  robust_kernel.h
  robust_kernel_impl.h
  robust_kernel_factory.h
  io_helper.h
  g2o_core_api.h
  hyper_graph.h
  hyper_graph_action.h
  DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/g2o/core)
