import { createApp, h } from 'vue'
import {
  ElAlert, ElButton, ElCard, ElCheckbox, ElColorPicker, ElConfigProvider, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElPopover, ElProgress, ElRadioButton, ElRadioGroup, ElSelect, ElSwitch,
} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/card/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/color-picker/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/dropdown/style/css'
import 'element-plus/es/components/dropdown-item/style/css'
import 'element-plus/es/components/dropdown-menu/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/popover/style/css'
import 'element-plus/es/components/progress/style/css'
import 'element-plus/es/components/radio-button/style/css'
import 'element-plus/es/components/radio-group/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/switch/style/css'
import AppGate from './AppGate.vue'
import './style.css'
import './revision.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './night.css'
import './library.css'
import './theme.css'
import './typography.css'
import './controls.css'

const app = createApp({
  render: () => h(ElConfigProvider, { locale: zhCn }, () => h(AppGate)),
})

for (const component of [
  ElAlert, ElButton, ElCard, ElCheckbox, ElColorPicker, ElDialog, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElPopover, ElProgress, ElRadioButton, ElRadioGroup, ElSelect, ElSwitch,
]) {
  app.component(component.name, component)
}

app.mount('#app')
