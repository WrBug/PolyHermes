import { useState, useEffect } from 'react'
import { Card, Form, Button, Input, InputNumber, Select, Switch, message, Space, Row, Col } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { backtestService } from '../services/api'
import { apiService } from '../services/api'
import type { Leader } from '../types'
import type { BacktestCreateRequest } from '../types/backtest'

const { Option } = Select

const BacktestCreate: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')

  // 获取 Leader 列表
  useEffect(() => {
    const fetchLeaders = async () => {
      try {
        const response = await apiService.leaders.list({})
        if (response.data.code === 0 && response.data.data) {
          setLeaders(response.data.data.list || [])
        }
      } catch (error) {
        console.error('Failed to fetch leaders:', error)
      }
    }
    fetchLeaders()
  }, [])

  // 提交表单
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      setLoading(true)

      const request: BacktestCreateRequest = {
        taskName: values.taskName,
        leaderId: values.leaderId,
        initialBalance: values.initialBalance,
        backtestDays: values.backtestDays,
        copyMode: values.copyMode || 'RATIO',
        copyRatio: values.copyMode === 'RATIO' ? values.copyRatio : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount : undefined,
        maxOrderSize: values.maxOrderSize,
        minOrderSize: values.minOrderSize,
        maxDailyLoss: values.maxDailyLoss,
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance,
        delaySeconds: values.delaySeconds,
        supportSell: values.supportSell,
        minOrderDepth: values.minOrderDepth,
        maxSpread: values.maxSpread,
        minPrice: values.minPrice,
        maxPrice: values.maxPrice,
        maxPositionValue: values.maxPositionValue,
        keywordFilterMode: values.keywordFilterMode,
        keywords: values.keywords,
        maxMarketEndDate: values.maxMarketEndDate
      }

      const response = await backtestService.create(request)
      if (response.data.code === 0) {
        message.success(t('backtest.createSuccess'))
        navigate('/backtest/list')
      } else {
        message.error(response.data.msg || t('backtest.createFailed'))
      }
    } catch (error) {
      console.error('Failed to create backtest task:', error)
      message.error(t('backtest.createFailed'))
    } finally {
      setLoading(false)
    }
  }

  // 返回
  const handleBack = () => {
    navigate('/backtest/list')
  }

  // 初始化表单默认值
  useEffect(() => {
    form.setFieldsValue({
      copyMode: 'RATIO',
      copyRatio: 1.0,
      maxOrderSize: 1000,
      minOrderSize: 1,
      maxDailyLoss: 500,
      maxDailyOrders: 50,
      priceTolerance: 5,
      delaySeconds: 0,
      supportSell: true,
      keywordFilterMode: 'DISABLED',
      backtestDays: 7
    })
  }, [form])

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card
        title={t('backtest.createTask')}
        extra={
          <Button icon={<ArrowLeftOutlined />} onClick={handleBack}>
            {t('common.back')}
          </Button>
        }
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 1.0,
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyLoss: 500,
            maxDailyOrders: 50,
            priceTolerance: 5,
            delaySeconds: 0,
            supportSell: true,
            keywordFilterMode: 'DISABLED',
            backtestDays: 7
          }}
        >
          <Row gutter={24}>
            <Col span={12}>
              <Form.Item
                label={t('backtest.taskName')}
                name="taskName"
                rules={[{ required: true, message: t('backtest.taskNameRequired') || '请输入任务名称' }]}
              >
                <Input placeholder={t('backtest.taskName')} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label={t('backtest.leader')}
                name="leaderId"
                rules={[{ required: true, message: t('backtest.leaderRequired') || '请选择 Leader' }]}
              >
                <Select placeholder={t('backtest.leader')} showSearch>
                  {leaders.map((leader) => (
                    <Option key={leader.id} value={leader.id}>
                      {leader.leaderName || leader.leaderAddress}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={24}>
            <Col span={12}>
              <Form.Item
                label={t('backtest.initialBalance') + ' (USDC)'}
                name="initialBalance"
                rules={[
                  { required: true, message: t('backtest.initialBalanceRequired') || '请输入初始资金' },
                  { type: 'number', min: 1, message: t('backtest.initialBalanceInvalid') || '初始资金必须大于 0' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.initialBalance')}
                  precision={2}
                  min={1}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label={t('backtest.backtestDays') + ` (1-15 ${t('common.day')})`}
                name="backtestDays"
                rules={[
                  { required: true, message: t('backtest.backtestDaysRequired') || '请输入回测天数' },
                  { type: 'number', min: 1, max: 15, message: t('backtest.backtestDaysInvalid') || '回测天数必须在 1-15 之间' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.backtestDays')}
                  precision={0}
                  min={1}
                  max={15}
                />
              </Form.Item>
            </Col>
          </Row>

          {/* 跟单配置 */}
          <div style={{ marginBottom: 24 }}>
            <h3 style={{ marginBottom: 16 }}>{t('backtest.config')}</h3>

            <Form.Item
              label={t('backtest.copyMode')}
              name="copyMode"
            >
              <Select onChange={(value) => setCopyMode(value)}>
                <Option value="RATIO">{t('backtest.copyModeRatio')}</Option>
                <Option value="FIXED">{t('backtest.copyModeFixed')}</Option>
              </Select>
            </Form.Item>

            {copyMode === 'RATIO' && (
              <Form.Item
                label={t('backtest.copyRatio')}
                name="copyRatio"
                rules={[
                  { required: true, message: t('backtest.copyRatioRequired') || '请输入跟单比例' },
                  { type: 'number', min: 0.01, max: 10, message: t('backtest.copyRatioInvalid') || '跟单比例必须在 0.01-10 之间' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.copyRatio')}
                  precision={2}
                  min={0.01}
                  max={10}
                  step={0.01}
                />
              </Form.Item>
            )}

            {copyMode === 'FIXED' && (
              <Form.Item
                label={t('backtest.fixedAmount') + ' (USDC)'}
                name="fixedAmount"
                rules={[
                  { required: true, message: t('backtest.fixedAmountRequired') || '请输入固定金额' },
                  { type: 'number', min: 1, message: t('backtest.fixedAmountInvalid') || '固定金额必须大于 0' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.fixedAmount')}
                  precision={2}
                  min={1}
                />
              </Form.Item>
            )}

            <Row gutter={24}>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.maxOrderSize') + ' (USDC)'}
                  name="maxOrderSize"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={1} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.minOrderSize') + ' (USDC)'}
                  name="minOrderSize"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={1} />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={24}>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.maxDailyLoss') + ' (USDC)'}
                  name="maxDailyLoss"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={0} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.maxDailyOrders')}
                  name="maxDailyOrders"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={0} min={1} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              label={t('backtest.priceTolerance') + ' (%)'}
              name="priceTolerance"
            >
              <InputNumber style={{ width: '100%' }} precision={2} min={0} max={100} />
            </Form.Item>

            <Form.Item
              label={t('backtest.delaySeconds')}
              name="delaySeconds"
            >
              <InputNumber style={{ width: '100%' }} precision={0} min={0} />
              <span style={{ fontSize: 12, color: '#888' }}>{t('backtest.delaySecondsHint') || '延迟执行模拟真实跟单延迟'}</span>
            </Form.Item>

            <Form.Item
              label={t('backtest.supportSell')}
              name="supportSell"
              valuePropName="checked"
            >
              <Switch />
              <span style={{ fontSize: 12, color: '#888', marginLeft: 8 }}>{t('backtest.supportSellHint') || '是否跟随 Leader 的卖出操作'}</span>
            </Form.Item>

            <h4 style={{ marginTop: 24, marginBottom: 16 }}>{t('backtest.advancedFilters')}</h4>

            <Row gutter={24}>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.minOrderDepth') + ' (USDC)'}
                  name="minOrderDepth"
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={0} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.maxSpread') + ' (%)'}
                  name="maxSpread"
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={0} />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={24}>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.minPrice')}
                  name="minPrice"
                >
                  <InputNumber style={{ width: '100%' }} precision={4} min={0} max={1} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label={t('backtest.maxPrice')}
                  name="maxPrice"
                >
                  <InputNumber style={{ width: '100%' }} precision={4} min={0} max={1} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              label={t('backtest.maxPositionValue') + ' (USDC)'}
              name="maxPositionValue"
            >
              <InputNumber style={{ width: '100%' }} precision={2} min={0} />
            </Form.Item>

            <Form.Item
              label={t('backtest.keywordFilterMode')}
              name="keywordFilterMode"
            >
              <Select>
                <Option value="DISABLED">{t('backtest.keywordFilterModeDisabled')}</Option>
                <Option value="WHITELIST">{t('backtest.keywordFilterModeWhitelist')}</Option>
                <Option value="BLACKLIST">{t('backtest.keywordFilterModeBlacklist')}</Option>
              </Select>
            </Form.Item>

            <Form.Item
              label={t('backtest.keywords')}
              name="keywords"
            >
              <Select
                mode="tags"
                style={{ width: '100%' }}
                placeholder={t('backtest.keywordsPlaceholder') || '请输入关键字，按回车添加'}
              />
            </Form.Item>
          </div>

          {/* 底部按钮 */}
          <Form.Item>
            <Space>
              <Button onClick={handleBack}>
                {t('common.cancel')}
              </Button>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('common.save')}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default BacktestCreate

