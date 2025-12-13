import { useEffect, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Button, Steps, message, Input, Select, Switch, InputNumber, DatePicker, Space, Divider, Checkbox } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { NbaQuantitativeStrategyCreateRequest, NbaGame } from '../types'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import dayjs, { Dayjs } from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// 配置 dayjs 时区插件
dayjs.extend(utc)
dayjs.extend(timezone)

const { Option } = Select
const { TextArea } = Input
const { RangePicker } = DatePicker

const NbaQuantitativeStrategyAdd: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [currentStep, setCurrentStep] = useState(0)
  const [games, setGames] = useState<NbaGame[]>([])
  const [loadingGames, setLoadingGames] = useState(false)
  const [selectedGameId, setSelectedGameId] = useState<string | null>(null)
  
  const fetchGames = useCallback(async () => {
    setLoadingGames(true)
    try {
      // 使用西8区时间计算时间戳
      const today = dayjs().tz('America/Los_Angeles').startOf('day')
      const nextWeek = dayjs().tz('America/Los_Angeles').add(7, 'day').endOf('day')
      
      const response = await apiService.nbaGames.list({
        startTimestamp: today.valueOf(),  // 传递时间戳（毫秒）
        endTimestamp: nextWeek.valueOf()  // 传递时间戳（毫秒）
      })
      if (response.data.code === 0 && response.data.data) {
        setGames(response.data.data.list || [])
      } else {
        message.warning('获取比赛列表失败，请稍后重试')
      }
    } catch (error: any) {
      message.error(error.message || '获取比赛列表失败')
    } finally {
      setLoadingGames(false)
    }
  }, [])
  
  useEffect(() => {
    fetchAccounts()
    fetchGames()
    // 设置默认值
    form.setFieldsValue({
      enabled: true,
      minWinProbabilityDiff: 0.1,
      minTradeValue: 0.05,
      buyAmountStrategy: 'FIXED',
      fixedBuyAmount: 10,
      buyTiming: 'IMMEDIATE',
      buyDirection: 'AUTO',
      enableSell: true,
      sellRatio: 1.0,
      sellTiming: 'IMMEDIATE',
      priceStrategy: 'MARKET',
      priceOffset: 0,
      maxPosition: 50,
      minPosition: 5,
      priceTolerance: 0.05,
      baseStrengthWeight: 0.3,
      recentFormWeight: 0.25,
      lineupIntegrityWeight: 0.2,
      starStatusWeight: 0.15,
      environmentWeight: 0.1,
      matchupAdvantageWeight: 0.2,
      scoreDiffWeight: 0.3,
      momentumWeight: 0.2,
      dataUpdateFrequency: 30,
      analysisFrequency: 30,
      pushFailedOrders: false,
      pushFrequency: 'REALTIME',
      batchPushInterval: 1
    })
  }, [fetchGames])
  
  const handleGameSelectionChange = (gameId: string | null) => {
    setSelectedGameId(gameId)
    if (gameId) {
      const selectedGame = games.find(game => game.nbaGameId === gameId)
      if (selectedGame) {
        // 自动提取该比赛的两支球队
        form.setFieldsValue({
          filterTeams: [selectedGame.homeTeam, selectedGame.awayTeam]
        })
      }
    } else {
      form.setFieldsValue({
        filterTeams: undefined
      })
    }
  }
  
  const steps = [
    { title: '基本信息', description: '策略名称和账户' },
    { title: '触发条件', description: '概率阈值和交易价值' },
    { title: '交易规则', description: '买入卖出规则' },
    { title: '风险控制', description: '持仓和每日限制' },
    { title: '高级配置', description: '算法权重和系统配置' }
  ]
  
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      setLoading(true)
      
      const request: NbaQuantitativeStrategyCreateRequest = {
        strategyName: values.strategyName,
        strategyDescription: values.strategyDescription,
        accountId: values.accountId,
        enabled: values.enabled,
        filterTeams: values.filterTeams,
        filterDateFrom: values.dateRange?.[0]?.format('YYYY-MM-DD'),
        filterDateTo: values.dateRange?.[1]?.format('YYYY-MM-DD'),
        filterGameImportance: values.filterGameImportance,
        minWinProbabilityDiff: values.minWinProbabilityDiff?.toString(),
        minWinProbability: values.minWinProbability?.toString(),
        maxWinProbability: values.maxWinProbability?.toString(),
        minTradeValue: values.minTradeValue?.toString(),
        minRemainingTime: values.minRemainingTime,
        maxRemainingTime: values.maxRemainingTime,
        minScoreDiff: values.minScoreDiff,
        maxScoreDiff: values.maxScoreDiff,
        buyAmountStrategy: values.buyAmountStrategy,
        fixedBuyAmount: values.fixedBuyAmount?.toString(),
        buyRatio: values.buyRatio?.toString(),
        baseBuyAmount: values.baseBuyAmount?.toString(),
        buyTiming: values.buyTiming,
        delayBuySeconds: values.delayBuySeconds,
        buyDirection: values.buyDirection,
        enableSell: values.enableSell,
        takeProfitThreshold: values.takeProfitThreshold?.toString(),
        stopLossThreshold: values.stopLossThreshold?.toString(),
        probabilityReversalThreshold: values.probabilityReversalThreshold?.toString(),
        sellRatio: values.sellRatio?.toString(),
        sellTiming: values.sellTiming,
        delaySellSeconds: values.delaySellSeconds,
        priceStrategy: values.priceStrategy,
        fixedPrice: values.fixedPrice?.toString(),
        priceOffset: values.priceOffset?.toString(),
        maxPosition: values.maxPosition?.toString(),
        minPosition: values.minPosition?.toString(),
        maxGamePosition: values.maxGamePosition?.toString(),
        maxDailyLoss: values.maxDailyLoss?.toString(),
        maxDailyOrders: values.maxDailyOrders,
        maxDailyProfit: values.maxDailyProfit?.toString(),
        priceTolerance: values.priceTolerance?.toString(),
        minProbabilityThreshold: values.minProbabilityThreshold?.toString(),
        maxProbabilityThreshold: values.maxProbabilityThreshold?.toString(),
        baseStrengthWeight: values.baseStrengthWeight?.toString(),
        recentFormWeight: values.recentFormWeight?.toString(),
        lineupIntegrityWeight: values.lineupIntegrityWeight?.toString(),
        starStatusWeight: values.starStatusWeight?.toString(),
        environmentWeight: values.environmentWeight?.toString(),
        matchupAdvantageWeight: values.matchupAdvantageWeight?.toString(),
        scoreDiffWeight: values.scoreDiffWeight?.toString(),
        momentumWeight: values.momentumWeight?.toString(),
        dataUpdateFrequency: values.dataUpdateFrequency,
        analysisFrequency: values.analysisFrequency,
        pushFailedOrders: values.pushFailedOrders,
        pushFrequency: values.pushFrequency,
        batchPushInterval: values.batchPushInterval
      }
      
      const response = await apiService.nbaStrategies.create(request)
      if (response.data.code === 0) {
        message.success('创建策略成功')
        navigate('/nba/strategies')
      } else {
        message.error(response.data.msg || '创建策略失败')
      }
    } catch (error: any) {
      if (error.errorFields) {
        // 表单验证错误
        const firstErrorField = error.errorFields[0]
        message.error(`${firstErrorField.name.join('.')}: ${firstErrorField.errors[0]}`)
      } else {
        message.error(error.message || '创建策略失败')
      }
    } finally {
      setLoading(false)
    }
  }
  
  const next = async () => {
    try {
      const fields = getFieldsForStep(currentStep)
      await form.validateFields(fields)
      setCurrentStep(currentStep + 1)
    } catch (error) {
      // 验证失败，不跳转
    }
  }
  
  const prev = () => {
    setCurrentStep(currentStep - 1)
  }
  
  const getFieldsForStep = (step: number): string[] => {
    switch (step) {
      case 0:
        return ['strategyName', 'accountId']
      case 1:
        return ['minWinProbabilityDiff', 'minTradeValue']
      case 2:
        return ['buyAmountStrategy', 'priceStrategy']
      case 3:
        return ['maxPosition', 'minPosition']
      default:
        return []
    }
  }
  
  const buyAmountStrategy = Form.useWatch('buyAmountStrategy', form)
  const priceStrategy = Form.useWatch('priceStrategy', form)
  const buyTiming = Form.useWatch('buyTiming', form)
  const sellTiming = Form.useWatch('sellTiming', form)
  const pushFrequency = Form.useWatch('pushFrequency', form)
  
  return (
    <div style={{ padding: isMobile ? '16px' : '24px' }}>
      <Card>
        <Space style={{ marginBottom: 24 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/nba/strategies')}>
            返回
          </Button>
        </Space>
        
        <Steps current={currentStep} items={steps} style={{ marginBottom: 32 }} />
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
        >
          {/* 第一步：基本信息 */}
          {currentStep === 0 && (
            <>
              <Form.Item
                name="strategyName"
                label="策略名称"
                rules={[{ required: true, message: '请输入策略名称' }]}
              >
                <Input placeholder="请输入策略名称" maxLength={50} />
              </Form.Item>
              
              <Form.Item
                name="strategyDescription"
                label="策略描述"
              >
                <TextArea rows={4} placeholder="请输入策略描述（可选）" maxLength={200} />
              </Form.Item>
              
              <Form.Item
                name="accountId"
                label="关联账户"
                rules={[{ required: true, message: '请选择关联账户' }]}
              >
                <Select placeholder="请选择账户">
                  {accounts.map(account => (
                    <Option key={account.id} value={account.id}>
                      {account.accountName || account.walletAddress}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
              
              <Form.Item
                name="enabled"
                label="启用状态"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              
              <Form.Item
                label="选择比赛（可选）"
                tooltip="选择一场比赛，系统会自动提取该比赛的两支球队作为关注球队"
              >
                <Select
                  placeholder="请选择一场比赛"
                  value={selectedGameId}
                  onChange={handleGameSelectionChange}
                  allowClear
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  style={{ width: '100%' }}
                >
                  {loadingGames ? (
                    <Option value="loading" disabled>加载中...</Option>
                  ) : games.length === 0 ? (
                    <Option value="empty" disabled>暂无比赛数据</Option>
                  ) : (
                    games.map(game => (
                      <Option
                        key={game.nbaGameId}
                        value={game.nbaGameId}
                        label={`${game.awayTeam} @ ${game.homeTeam} (${dayjs(game.gameDate).format('MM-DD')}${game.gameTime ? ` ${dayjs(game.gameTime).tz('America/Los_Angeles').format('HH:mm')}` : ''})`}
                      >
                        <div>
                          <span style={{ fontWeight: 500 }}>
                            {game.awayTeam} @ {game.homeTeam}
                          </span>
                          <span style={{ marginLeft: '8px', color: '#999', fontSize: '12px' }}>
                            {dayjs(game.gameDate).format('MM-DD')}
                            {game.gameTime && ` ${dayjs(game.gameTime).tz('America/Los_Angeles').format('HH:mm')}`}
                          </span>
                          {game.gameStatus && (
                            <span style={{ marginLeft: '8px', color: '#666', fontSize: '12px' }}>
                              ({game.gameStatus === 'scheduled' ? '未开始' : game.gameStatus === 'active' ? '进行中' : '已结束'})
                            </span>
                          )}
                        </div>
                      </Option>
                    ))
                  )}
                </Select>
                {selectedGameId && (
                  <div style={{ marginTop: '8px', fontSize: '12px', color: '#666' }}>
                    已选择比赛，系统将自动关注该比赛的两支球队
                  </div>
                )}
                <Form.Item name="filterTeams" hidden>
                  <Input />
                </Form.Item>
              </Form.Item>
              
              <Form.Item
                name="dateRange"
                label="日期范围（可选）"
              >
                <RangePicker style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="filterGameImportance"
                label="比赛重要性"
              >
                <Select placeholder="选择比赛重要性">
                  <Option value="all">全部</Option>
                  <Option value="regular">常规赛</Option>
                  <Option value="playoff">季后赛</Option>
                  <Option value="key">关键战</Option>
                </Select>
              </Form.Item>
            </>
          )}
          
          {/* 第二步：触发条件 */}
          {currentStep === 1 && (
            <>
              <Form.Item
                name="minWinProbabilityDiff"
                label="最小获胜概率差异"
                rules={[{ required: true, message: '请输入最小获胜概率差异' }]}
                tooltip="主队和客队获胜概率的最小差异（如 0.1 表示至少 10% 的差异）"
              >
                <InputNumber min={0.05} max={0.5} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="minWinProbability"
                label="最小获胜概率（可选）"
                tooltip="生成买入信号时的最小获胜概率"
              >
                <InputNumber min={0.5} max={1.0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxWinProbability"
                label="最大获胜概率（可选）"
                tooltip="生成买入信号时的最大获胜概率（用于反向策略）"
              >
                <InputNumber min={0.0} max={0.5} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="minTradeValue"
                label="最小交易价值"
                rules={[{ required: true, message: '请输入最小交易价值' }]}
                tooltip="交易价值评分的最小值，只有达到此值才会生成信号"
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="minRemainingTime"
                label="最小剩余时间（分钟，可选）"
              >
                <InputNumber min={0} max={48} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxRemainingTime"
                label="最大剩余时间（分钟，可选）"
              >
                <InputNumber min={0} max={48} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="minScoreDiff"
                label="最小分差（可选）"
              >
                <InputNumber min={-50} max={50} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxScoreDiff"
                label="最大分差（可选）"
              >
                <InputNumber min={-50} max={50} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
          
          {/* 第三步：交易规则 */}
          {currentStep === 2 && (
            <>
              <Divider orientation="left">买入规则</Divider>
              
              <Form.Item
                name="buyAmountStrategy"
                label="买入金额策略"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value="FIXED">固定金额</Option>
                  <Option value="RATIO">按比例</Option>
                  <Option value="DYNAMIC">动态计算</Option>
                </Select>
              </Form.Item>
              
              {buyAmountStrategy === 'FIXED' && (
                <Form.Item
                  name="fixedBuyAmount"
                  label="固定买入金额（USDC）"
                  rules={[{ required: true, message: '请输入固定买入金额' }]}
                >
                  <InputNumber min={0.01} step={0.1} style={{ width: '100%' }} />
                </Form.Item>
              )}
              
              {buyAmountStrategy === 'RATIO' && (
                <Form.Item
                  name="buyRatio"
                  label="买入比例（0-1）"
                  rules={[{ required: true, message: '请输入买入比例' }]}
                >
                  <InputNumber min={0.01} max={1} step={0.01} style={{ width: '100%' }} />
                </Form.Item>
              )}
              
              {buyAmountStrategy === 'DYNAMIC' && (
                <Form.Item
                  name="baseBuyAmount"
                  label="基础买入金额（USDC）"
                  rules={[{ required: true, message: '请输入基础买入金额' }]}
                >
                  <InputNumber min={0.01} step={0.1} style={{ width: '100%' }} />
                </Form.Item>
              )}
              
              <Form.Item
                name="buyTiming"
                label="买入时机"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value="IMMEDIATE">立即买入</Option>
                  <Option value="DELAYED">延迟买入</Option>
                </Select>
              </Form.Item>
              
              {buyTiming === 'DELAYED' && (
                <Form.Item
                  name="delayBuySeconds"
                  label="延迟买入时间（秒）"
                  rules={[{ required: true, message: '请输入延迟买入时间' }]}
                >
                  <InputNumber min={0} max={300} style={{ width: '100%' }} />
                </Form.Item>
              )}
              
              <Form.Item
                name="buyDirection"
                label="买入方向"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value="AUTO">系统自动判断</Option>
                  <Option value="YES">YES</Option>
                  <Option value="NO">NO</Option>
                </Select>
              </Form.Item>
              
              <Divider orientation="left">卖出规则</Divider>
              
              <Form.Item
                name="enableSell"
                label="启用卖出"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              
              <Form.Item
                name="takeProfitThreshold"
                label="止盈阈值（0-1，可选）"
                tooltip="预期收益达到多少时卖出（如 0.2 表示 20%）"
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="stopLossThreshold"
                label="止损阈值（-1-0，可选）"
                tooltip="预期亏损达到多少时卖出（如 -0.1 表示 -10%）"
              >
                <InputNumber min={-1} max={0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="probabilityReversalThreshold"
                label="概率反转阈值（0-1，可选）"
                tooltip="获胜概率反转多少时卖出（如 0.15 表示 15%）"
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="sellRatio"
                label="卖出比例（0-1）"
                rules={[{ required: true }]}
              >
                <InputNumber min={0.1} max={1} step={0.1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="sellTiming"
                label="卖出时机"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value="IMMEDIATE">立即卖出</Option>
                  <Option value="DELAYED">延迟卖出</Option>
                </Select>
              </Form.Item>
              
              {sellTiming === 'DELAYED' && (
                <Form.Item
                  name="delaySellSeconds"
                  label="延迟卖出时间（秒）"
                  rules={[{ required: true, message: '请输入延迟卖出时间' }]}
                >
                  <InputNumber min={0} max={300} style={{ width: '100%' }} />
                </Form.Item>
              )}
              
              <Divider orientation="left">价格策略</Divider>
              
              <Form.Item
                name="priceStrategy"
                label="价格策略"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value="FIXED">固定价格</Option>
                  <Option value="MARKET">市场价格</Option>
                  <Option value="DYNAMIC">动态价格</Option>
                </Select>
              </Form.Item>
              
              {priceStrategy === 'FIXED' && (
                <Form.Item
                  name="fixedPrice"
                  label="固定价格（0-1）"
                  rules={[{ required: true, message: '请输入固定价格' }]}
                >
                  <InputNumber min={0.01} max={0.99} step={0.01} style={{ width: '100%' }} />
                </Form.Item>
              )}
              
              <Form.Item
                name="priceOffset"
                label="价格偏移（-0.1-0.1）"
                tooltip="价格偏移百分比（用于调整价格，提高交易成功率，如 0.05 表示 +5%）"
              >
                <InputNumber min={-0.1} max={0.1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
          
          {/* 第四步：风险控制 */}
          {currentStep === 3 && (
            <>
              <Form.Item
                name="maxPosition"
                label="最大持仓（USDC）"
                rules={[{ required: true, message: '请输入最大持仓' }]}
              >
                <InputNumber min={1} step={1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="minPosition"
                label="最小持仓（USDC）"
                rules={[{ required: true, message: '请输入最小持仓' }]}
              >
                <InputNumber min={1} step={1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxGamePosition"
                label="单场比赛最大持仓（USDC，可选）"
              >
                <InputNumber min={1} step={1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxDailyLoss"
                label="每日亏损限制（USDC，可选）"
              >
                <InputNumber min={0.01} step={1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxDailyOrders"
                label="每日订单限制（可选）"
              >
                <InputNumber min={1} step={1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxDailyProfit"
                label="每日盈利目标（USDC，可选）"
              >
                <InputNumber min={0.01} step={1} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="priceTolerance"
                label="价格容忍度（0-1）"
                tooltip="允许的价格偏差百分比（如 0.05 表示 5%）"
              >
                <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="minProbabilityThreshold"
                label="最小概率阈值（0.5-1.0，可选）"
              >
                <InputNumber min={0.5} max={1.0} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="maxProbabilityThreshold"
                label="最大概率阈值（0.0-0.5，可选）"
              >
                <InputNumber min={0.0} max={0.5} step={0.01} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
          
          {/* 第五步：高级配置 */}
          {currentStep === 4 && (
            <>
              <Divider orientation="left">算法权重（高级）</Divider>
              
              <Form.Item
                name="baseStrengthWeight"
                label="基础实力权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="recentFormWeight"
                label="近期状态权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="lineupIntegrityWeight"
                label="阵容完整度权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="starStatusWeight"
                label="球星状态权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="environmentWeight"
                label="环境因素权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="matchupAdvantageWeight"
                label="对位优势权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="scoreDiffWeight"
                label="分差调整权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Form.Item
                name="momentumWeight"
                label="势头调整权重"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
              
              <Divider orientation="left">系统配置</Divider>
              
              <Form.Item
                name="dataUpdateFrequency"
                label="数据更新频率（秒）"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value={10}>10 秒</Option>
                  <Option value={30}>30 秒</Option>
                  <Option value={60}>1 分钟</Option>
                  <Option value={300}>5 分钟</Option>
                </Select>
              </Form.Item>
              
              <Form.Item
                name="analysisFrequency"
                label="分析频率（秒）"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value={10}>10 秒</Option>
                  <Option value={30}>30 秒</Option>
                  <Option value={60}>1 分钟</Option>
                  <Option value={300}>5 分钟</Option>
                </Select>
              </Form.Item>
              
              <Form.Item
                name="pushFailedOrders"
                label="推送失败订单"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
              
              <Form.Item
                name="pushFrequency"
                label="推送频率"
                rules={[{ required: true }]}
              >
                <Select>
                  <Option value="REALTIME">实时推送</Option>
                  <Option value="BATCH">批量推送</Option>
                </Select>
              </Form.Item>
              
              {pushFrequency === 'BATCH' && (
                <Form.Item
                  name="batchPushInterval"
                  label="批量推送间隔（秒）"
                  rules={[{ required: true, message: '请输入批量推送间隔' }]}
                >
                  <InputNumber min={1} max={60} style={{ width: '100%' }} />
                </Form.Item>
              )}
            </>
          )}
          
          <div style={{ marginTop: 24, textAlign: 'right' }}>
            <Space>
              {currentStep > 0 && (
                <Button onClick={prev}>
                  上一步
                </Button>
              )}
              {currentStep < steps.length - 1 && (
                <Button type="primary" onClick={next}>
                  下一步
                </Button>
              )}
              {currentStep === steps.length - 1 && (
                <Button type="primary" htmlType="submit" loading={loading} icon={<SaveOutlined />}>
                  创建策略
                </Button>
              )}
            </Space>
          </div>
        </Form>
      </Card>
    </div>
  )
}

export default NbaQuantitativeStrategyAdd

