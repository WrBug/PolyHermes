package com.wrbug.polymarketbot.controller.backtest

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.backtest.BacktestService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 回测管理控制器
 */
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(BacktestController::class.java)

    /**
     * 创建回测任务
     */
    @PostMapping("/tasks")
    fun createBacktestTask(@RequestBody request: BacktestCreateRequest): ResponseEntity<ApiResponse<BacktestTaskDto>> {
        return try {
            logger.info("创建回测任务: taskName=${request.taskName}, leaderId=${request.leaderId}")

            val result = runBlocking {
                backtestService.createBacktestTask(request)
            }

            result.fold(
                onSuccess = { dto ->
                    logger.info("回测任务创建成功: taskId=${dto.id}")
                    ResponseEntity.ok(ApiResponse.success(dto))
                },
                onFailure = { e ->
                    logger.error("创建回测任务失败", e)
                    val errorCode = when (e) {
                        is IllegalArgumentException -> ErrorCode.PARAM_ERROR
                        else -> ErrorCode.SERVER_BACKTEST_CREATE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("创建回测任务异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_CREATE_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询回测任务列表
     */
    @PostMapping("/tasks/list")
    fun getBacktestTaskList(@RequestBody request: BacktestListRequest): ResponseEntity<ApiResponse<BacktestListResponse>> {
        return try {
            val result = backtestService.getBacktestTaskList(request)

            result.fold(
                onSuccess = { response ->
                    logger.info("查询回测任务列表成功: total=${response.total}")
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询回测任务列表失败", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询回测任务列表异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询回测任务详情
     */
    @PostMapping("/tasks/detail")
    fun getBacktestTaskDetail(@RequestBody request: BacktestDetailRequest): ResponseEntity<ApiResponse<BacktestDetailResponse>> {
        return try {
            val result = backtestService.getBacktestTaskDetail(request)

            result.fold(
                onSuccess = { response ->
                    logger.info("查询回测任务详情成功: taskId=${request.id}")
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询回测任务详情失败", e)
                    val errorCode = when (e) {
                        is IllegalArgumentException -> ErrorCode.BACKTEST_TASK_NOT_FOUND
                        else -> ErrorCode.SERVER_BACKTEST_DETAIL_FETCH_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询回测任务详情异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_DETAIL_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 查询回测交易记录
     */
    @PostMapping("/tasks/trades")
    fun getBacktestTrades(@RequestBody request: BacktestTradeListRequest): ResponseEntity<ApiResponse<BacktestTradeListResponse>> {
        return try {
            val result = backtestService.getBacktestTrades(request)

            result.fold(
                onSuccess = { response ->
                    logger.info("查询回测交易记录成功: taskId=${request.taskId}")
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询回测交易记录失败", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_TRADES_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询回测交易记录异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_TRADES_FETCH_FAILED, e.message, messageSource))
        }
    }

    /**
     * 删除回测任务
     */
    @PostMapping("/tasks/delete")
    fun deleteBacktestTask(@RequestBody request: BacktestDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            logger.info("删除回测任务: taskId=${request.id}")

            val result = backtestService.deleteBacktestTask(request)

            result.fold(
                onSuccess = {
                    logger.info("回测任务删除成功: taskId=${request.id}")
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除回测任务失败", e)
                    val errorCode = when (e) {
                        is IllegalArgumentException -> ErrorCode.BACKTEST_TASK_NOT_FOUND
                        else -> ErrorCode.SERVER_BACKTEST_DELETE_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("删除回测任务异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_DELETE_FAILED, e.message, messageSource))
        }
    }

    /**
     * 停止回测任务
     */
    @PostMapping("/tasks/stop")
    fun stopBacktestTask(@RequestBody request: BacktestStopRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            logger.info("停止回测任务: taskId=${request.id}")

            val result = backtestService.stopBacktestTask(request)

            result.fold(
                onSuccess = {
                    logger.info("回测任务停止成功: taskId=${request.id}")
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("停止回测任务失败", e)
                    val errorCode = when (e) {
                        is IllegalArgumentException -> ErrorCode.BACKTEST_TASK_NOT_FOUND
                        is IllegalStateException -> ErrorCode.BACKTEST_TASK_RUNNING
                        else -> ErrorCode.SERVER_BACKTEST_STOP_FAILED
                    }
                    ResponseEntity.ok(ApiResponse.error(errorCode, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("停止回测任务异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_BACKTEST_STOP_FAILED, e.message, messageSource))
        }
    }
}

