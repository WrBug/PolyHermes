import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card, Form, Button, Switch, message, Typography, Space, Radio, InputNumber, Divider, Spin, Select, Input } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import type { CopyTrading, CopyTradingUpdateRequest } from '../types'
import { useTranslation } from 'react-i18next'

const { Title } = Typography
const { Option } = Select

const CopyTradingEdit: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { id } = useParams<{ id: string }>()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)
  const [copyTrading, setCopyTrading] = useState<CopyTrading | null>(null)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  const [originalEnabled, setOriginalEnabled] = useState<boolean>(true)
  
  useEffect(() => {
    if (id) {
      fetchCopyTrading(parseInt(id))
    }
  }, [id])
  
  const fetchCopyTrading = async (copyTradingId: number) => {
    setFetching(true)
    try {
      const response = await apiService.copyTrading.list({})
      if (response.data.code === 0 && response.data.data) {
        const found = response.data.data.list.find((ct: CopyTrading) => ct.id === copyTradingId)
        if (found) {
          setCopyTrading(found)
          setCopyMode(found.copyMode)
          setOriginalEnabled(found.enabled) // 保存原始的enabled状态
          // 填充表单数据
          form.setFieldsValue({
            accountId: found.accountId,
            leaderId: found.leaderId,
            copyMode: found.copyMode,
            copyRatio: found.copyRatio ? parseFloat(found.copyRatio) * 100 : 100, // 转换为百分比显示
            fixedAmount: found.fixedAmount ? parseFloat(found.fixedAmount) : undefined,
            maxOrderSize: found.maxOrderSize ? parseFloat(found.maxOrderSize) : undefined,
            minOrderSize: found.minOrderSize ? parseFloat(found.minOrderSize) : undefined,
            maxDailyLoss: found.maxDailyLoss ? parseFloat(found.maxDailyLoss) : undefined,
            maxDailyOrders: found.maxDailyOrders,
            priceTolerance: found.priceTolerance ? parseFloat(found.priceTolerance) : undefined,
            delaySeconds: found.delaySeconds,
            pollIntervalSeconds: found.pollIntervalSeconds,
            useWebSocket: found.useWebSocket,
            websocketReconnectInterval: found.websocketReconnectInterval,
            websocketMaxRetries: found.websocketMaxRetries,
            supportSell: found.supportSell,
            minOrderDepth: found.minOrderDepth ? parseFloat(found.minOrderDepth) : undefined,
            maxSpread: found.maxSpread ? parseFloat(found.maxSpread) : undefined,
            minPrice: found.minPrice ? parseFloat(found.minPrice) : undefined,
            maxPrice: found.maxPrice ? parseFloat(found.maxPrice) : undefined,
            maxPositionValue: found.maxPositionValue ? parseFloat(found.maxPositionValue) : undefined,
            maxPositionCount: found.maxPositionCount,
            configName: found.configName || '',
            pushFailedOrders: found.pushFailedOrders ?? false
          })
        } else {
          message.error(t('copyTradingEdit.fetchFailed') || '跟单配置不存在')
          navigate('/copy-trading')
        }
      } else {
        message.error(response.data.msg || t('copyTradingEdit.fetchFailed') || '获取跟单配置失败')
        navigate('/copy-trading')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.fetchFailed') || '获取跟单配置失败')
      navigate('/copy-trading')
    } finally {
      setFetching(false)
    }
  }
  
  const handleCopyModeChange = (mode: 'RATIO' | 'FIXED') => {
    setCopyMode(mode)
  }
  
  const handleSubmit = async (values: any) => {
    // 前端校验
    if (values.copyMode === 'FIXED') {
      if (!values.fixedAmount || Number(values.fixedAmount) < 1) {
        message.error('固定金额必须 >= 1')
        return
      }
    }
    
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error('最小金额必须 >= 1')
      return
    }
    
    if (!id) {
      message.error('配置ID不存在')
      return
    }
    
    setLoading(true)
    try {
      const request: CopyTradingUpdateRequest = {
        copyTradingId: parseInt(id),
        enabled: originalEnabled, // 保持原有的enabled状态，不修改
        copyMode: values.copyMode,
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.maxOrderSize?.toString(),
        minOrderSize: values.minOrderSize?.toString(),
        maxDailyLoss: values.maxDailyLoss?.toString(),
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        delaySeconds: values.delaySeconds,
        pollIntervalSeconds: values.pollIntervalSeconds,
        useWebSocket: values.useWebSocket,
        websocketReconnectInterval: values.websocketReconnectInterval,
        websocketMaxRetries: values.websocketMaxRetries,
        supportSell: values.supportSell,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        maxPositionValue: values.maxPositionValue?.toString(),
        maxPositionCount: values.maxPositionCount,
        configName: values.configName?.trim() || undefined,
        pushFailedOrders: values.pushFailedOrders
      }
      
      const response = await apiService.copyTrading.update(request)
      
      if (response.data.code === 0) {
        message.success(t('copyTradingEdit.saveSuccess') || '更新跟单配置成功')
        navigate('/copy-trading')
      } else {
        message.error(response.data.msg || t('copyTradingEdit.saveFailed') || '更新跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.saveFailed') || '更新跟单配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  if (fetching) {
    return (
      <div style={{ textAlign: 'center', padding: '40px' }}>
        <Spin size="large" />
      </div>
    )
  }
  
  if (!copyTrading) {
    return null
  }
  
  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/copy-trading')}
        >
          {t('common.back') || '返回'}
        </Button>
      </div>
      
      <Card>
        <Title level={4}>{t('copyTradingEdit.title') || '编辑跟单配置'}</Title>
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
        >
          {/* 基础信息（只读） */}
          <Form.Item
            label={t('copyTradingEdit.configName') || '配置名'}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingEdit.configNameRequired') || '请输入配置名' },
              { whitespace: true, message: t('copyTradingEdit.configNameRequired') || '配置名不能为空' }
            ]}
            tooltip={t('copyTradingEdit.configNameTooltip') || '为跟单配置设置一个名称，便于识别和管理'}
          >
            <Input 
              placeholder={t('copyTradingEdit.configNamePlaceholder') || '例如：跟单配置1'} 
              maxLength={255}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectWallet') || t('copyTradingEdit.selectWallet') || '钱包'}
            name="accountId"
          >
            <Select disabled>
              <Option value={copyTrading.accountId}>
                {copyTrading.accountName || `账户 ${copyTrading.accountId}`} ({copyTrading.walletAddress.slice(0, 6)}...{copyTrading.walletAddress.slice(-4)})
              </Option>
            </Select>
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectLeader') || t('copyTradingEdit.selectLeader') || 'Leader'}
            name="leaderId"
          >
            <Select disabled>
              <Option value={copyTrading.leaderId}>
                {copyTrading.leaderName || `Leader ${copyTrading.leaderId}`} ({copyTrading.leaderAddress.slice(0, 6)}...{copyTrading.leaderAddress.slice(-4)})
              </Option>
            </Select>
          </Form.Item>
          
          <Divider>{t('copyTradingEdit.basicConfig') || '基础配置'}</Divider>
     
          {/* 跟单金额模式 */}
          <Form.Item
            label={t('copyTradingEdit.copyMode') || '跟单金额模式'}
            name="copyMode"
            tooltip={t('copyTradingEdit.copyModeTooltip') || '选择跟单金额的计算方式'}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingEdit.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('copyTradingEdit.fixedAmountMode') || '固定金额模式'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('copyTradingEdit.copyRatio') || '跟单比例'}
              name="copyRatio"
              tooltip={t('copyTradingEdit.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比'}
            >
              <InputNumber
                min={10}
                max={1000}
                step={1}
                precision={0}
                style={{ width: '100%' }}
                addonAfter="%"
                placeholder={t('copyTradingEdit.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单）'}
              />
            </Form.Item>
          )}
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('copyTradingEdit.fixedAmount') || '固定跟单金额 (USDC)'}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingEdit.fixedAmountRequired') || '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingEdit.invalidNumber') || '请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingEdit.fixedAmountMin') || '固定金额必须 >= 1'))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                min={1}
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder={t('copyTradingEdit.fixedAmountPlaceholder') || '固定金额，不随 Leader 订单大小变化，必须 >= 1'}
              />
            </Form.Item>
          )}
          
          {copyMode === 'RATIO' && (
            <>
              <Form.Item
                label={t('copyTradingEdit.maxOrderSize') || '单笔订单最大金额 (USDC)'}
                name="maxOrderSize"
                tooltip={t('copyTradingEdit.maxOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最大金额上限'}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingEdit.maxOrderSizePlaceholder') || '仅在比例模式下生效（可选）'}
                />
              </Form.Item>
              
              <Form.Item
                label={t('copyTradingEdit.minOrderSize') || '单笔订单最小金额 (USDC)'}
                name="minOrderSize"
                tooltip={t('copyTradingEdit.minOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最小金额下限，必须 >= 1'}
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('copyTradingEdit.minOrderSizeMin') || '最小金额必须 >= 1'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <InputNumber
                  min={1}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingEdit.minOrderSizePlaceholder') || '仅在比例模式下生效，必须 >= 1（可选）'}
                />
              </Form.Item>
            </>
          )}
          
          <Form.Item
            label={t('copyTradingEdit.maxDailyLoss') || '每日最大亏损限制 (USDC)'}
            name="maxDailyLoss"
            tooltip={t('copyTradingEdit.maxDailyLossTooltip') || '限制每日最大亏损金额，用于风险控制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxDailyLossPlaceholder') || '默认 10000 USDC（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.maxDailyOrders') || '每日最大跟单订单数'}
            name="maxDailyOrders"
            tooltip={t('copyTradingEdit.maxDailyOrdersTooltip') || '限制每日最多跟单的订单数量'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxDailyOrdersPlaceholder') || '默认 100（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.priceTolerance') || '价格容忍度 (%)'}
            name="priceTolerance"
            tooltip={t('copyTradingEdit.priceToleranceTooltip') || '允许跟单价格在 Leader 价格基础上的调整范围'}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.priceTolerancePlaceholder') || '默认 5%（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.delaySeconds') || '跟单延迟 (秒)'}
            name="delaySeconds"
            tooltip={t('copyTradingEdit.delaySecondsTooltip') || '跟单延迟时间，0 表示立即跟单'}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.delaySecondsPlaceholder') || '默认 0（立即跟单）'}
            />
          </Form.Item>
   
          <Form.Item
            label={t('copyTradingEdit.minOrderDepth') || '最小订单深度 (USDC)'}
            name="minOrderDepth"
            tooltip={t('copyTradingEdit.minOrderDepthTooltip') || '检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.minOrderDepthPlaceholder') || '例如：100（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.maxSpread') || '最大价差（绝对价格）'}
            name="maxSpread"
            tooltip={t('copyTradingEdit.maxSpreadTooltip') || '最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxSpreadPlaceholder') || '例如：0.05（5美分，可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingEdit.priceRangeFilter') || '价格区间过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.priceRange') || '价格区间'}
            name="priceRange"
            tooltip={t('copyTradingEdit.priceRangeTooltip') || '配置价格区间，仅在指定价格区间内的订单才会下单。例如：0.11-0.89 表示区间在0.11和0.89之间；-0.89 表示0.89以下都可以；0.11- 表示0.11以上都可以'}
          >
            <Input.Group compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingEdit.minPricePlaceholder') || '最低价（可选）'}
                />
              </Form.Item>
              <span style={{ display: 'inline-block', width: '20px', textAlign: 'center', lineHeight: '32px' }}>-</span>
              <Form.Item name="maxPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingEdit.maxPricePlaceholder') || '最高价（可选）'}
                />
              </Form.Item>
            </Input.Group>
          </Form.Item>
          
          <Divider>{t('copyTradingEdit.positionLimitFilter') || '最大仓位限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingEdit.maxPositionValue') || '最大仓位金额 (USDC)'}
            name="maxPositionValue"
            tooltip={t('copyTradingEdit.maxPositionValueTooltip') || '限制单个市场的最大仓位金额。如果该市场的当前仓位金额 + 跟单金额超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxPositionValuePlaceholder') || '例如：100（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingEdit.maxPositionCount') || '最大仓位数量'}
            name="maxPositionCount"
            tooltip={t('copyTradingEdit.maxPositionCountTooltip') || '限制单个市场的最大仓位数量。如果该市场的当前仓位数量达到或超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingEdit.maxPositionCountPlaceholder') || '例如：10（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingEdit.advancedSettings') || '高级设置'}</Divider>
          
          {/* 跟单卖出 */}
          <Form.Item
            label={t('copyTradingEdit.supportSell') || '跟单卖出'}
            name="supportSell"
            tooltip={t('copyTradingEdit.supportSellTooltip') || '是否跟单 Leader 的卖出订单'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 推送失败订单 */}
          <Form.Item
            label={t('copyTradingEdit.pushFailedOrders') || '推送失败订单'}
            name="pushFailedOrders"
            tooltip={t('copyTradingEdit.pushFailedOrdersTooltip') || '开启后，失败的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('copyTradingEdit.save') || '保存'}
              </Button>
              <Button onClick={() => navigate('/copy-trading')}>
                {t('common.cancel') || '取消'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default CopyTradingEdit

