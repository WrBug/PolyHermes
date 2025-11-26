import { useEffect, useState } from 'react'
import { Card, Form, Button, Switch, Radio, InputNumber, message, Typography } from 'antd'
import { SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import type { CopyTradingConfig } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const ConfigPage: React.FC = () => {
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [, setConfig] = useState<CopyTradingConfig | null>(null)
  
  useEffect(() => {
    fetchConfig()
  }, [])
  
  const fetchConfig = async () => {
    try {
      const response = await apiService.config.get()
      if (response.data.code === 0 && response.data.data) {
        const data = response.data.data
        setConfig(data)
        form.setFieldsValue(data)
      } else {
        message.error(response.data.msg || '获取配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取配置失败')
    }
  }
  
  const handleSubmit = async (values: any) => {
    setLoading(true)
    try {
      const response = await apiService.config.update(values)
      if (response.data.code === 0) {
        message.success('更新配置成功')
        fetchConfig()
      } else {
        message.error(response.data.msg || '更新配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '更新配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>跟单配置</Title>
      </div>
      
      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          size={isMobile ? 'middle' : 'large'}
        >
          <Form.Item
            label="跟单金额模式"
            name="copyMode"
            rules={[{ required: true, message: '请选择跟单金额模式' }]}
          >
            <Radio.Group>
              <Radio value="RATIO">比例模式</Radio>
              <Radio value="FIXED">固定金额模式</Radio>
            </Radio.Group>
          </Form.Item>
          
          <Form.Item
            noStyle
            shouldUpdate={(prevValues, currentValues) => prevValues.copyMode !== currentValues.copyMode}
          >
            {({ getFieldValue }) => {
              const copyMode = getFieldValue('copyMode')
              return copyMode === 'RATIO' ? (
                <Form.Item
                  label="跟单比例"
                  name="copyRatio"
                  rules={[{ required: true, message: '请输入跟单比例' }]}
                  help="跟单金额 = Leader 订单金额 × 跟单比例"
                >
                  <InputNumber
                    min={0.1}
                    max={10}
                    step={0.1}
                    style={{ width: '100%' }}
                    placeholder="例如：1.0 表示 1:1 跟单"
                  />
                </Form.Item>
              ) : (
                <Form.Item
                  label="固定跟单金额"
                  name="fixedAmount"
                  rules={[{ required: true, message: '请输入固定跟单金额' }]}
                  help="无论 Leader 订单大小如何，跟单金额都固定"
                >
                  <InputNumber
                    min={0.01}
                    step={0.01}
                    style={{ width: '100%' }}
                    placeholder="USDC"
                  />
                </Form.Item>
              )
            }}
          </Form.Item>
          
          <Form.Item
            label="单笔订单最大金额"
            name="maxOrderSize"
            rules={[{ required: true, message: '请输入最大金额' }]}
          >
            <InputNumber
              min={0.01}
              step={0.01}
              style={{ width: '100%' }}
              placeholder="USDC"
            />
          </Form.Item>
          
          <Form.Item
            label="单笔订单最小金额"
            name="minOrderSize"
            rules={[{ required: true, message: '请输入最小金额' }]}
          >
            <InputNumber
              min={0.01}
              step={0.01}
              style={{ width: '100%' }}
              placeholder="USDC"
            />
          </Form.Item>
          
          <Form.Item
            label="每日最大亏损限制"
            name="maxDailyLoss"
            rules={[{ required: true, message: '请输入最大亏损限制' }]}
          >
            <InputNumber
              min={0}
              step={0.01}
              style={{ width: '100%' }}
              placeholder="USDC"
            />
          </Form.Item>
          
          <Form.Item
            label="每日最大跟单订单数"
            name="maxDailyOrders"
            rules={[{ required: true, message: '请输入最大订单数' }]}
          >
            <InputNumber
              min={1}
              style={{ width: '100%' }}
            />
          </Form.Item>
          
          <Form.Item
            label="价格容忍度"
            name="priceTolerance"
            rules={[{ required: true, message: '请输入价格容忍度' }]}
            help="百分比，允许价格在 Leader 价格 ± 容忍度范围内调整"
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              style={{ width: '100%' }}
              addonAfter="%"
            />
          </Form.Item>
          
          <Form.Item
            label="跟单延迟"
            name="delaySeconds"
            rules={[{ required: true, message: '请输入跟单延迟' }]}
            help="延迟 N 秒后跟单（0 表示立即跟单）"
          >
            <InputNumber
              min={0}
              style={{ width: '100%' }}
              addonAfter="秒"
            />
          </Form.Item>
          
          <Form.Item
            label="轮询间隔"
            name="pollIntervalSeconds"
            rules={[{ required: true, message: '请输入轮询间隔' }]}
            help="轮询 Leader 交易的间隔（仅在 WebSocket 不可用时使用）"
          >
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              addonAfter="秒"
            />
          </Form.Item>
          
          <Form.Item
            label="优先使用 WebSocket 推送"
            name="useWebSocket"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label="启用全局跟单"
            name="enabled"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
              loading={loading}
              size={isMobile ? 'middle' : 'large'}
            >
              保存配置
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default ConfigPage

